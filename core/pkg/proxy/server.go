package proxy

import (
	"context"
	"encoding/binary"
	"fmt"
	"io"
	"log"
	"net"
	"sync"
	"time"

	"tg-ws-proxy-core/pkg/config"
	"tg-ws-proxy-core/pkg/crypto"
	"tg-ws-proxy-core/pkg/faketls"
	"tg-ws-proxy-core/pkg/proto"
	"tg-ws-proxy-core/pkg/relay"
	"tg-ws-proxy-core/pkg/ws"
)

// Server coordinates listening for client connections, authentication, and routing to Telegram.
type Server struct {
	cfg      *config.Config
	listener net.Listener
	mu       sync.Mutex
	closed   bool
}

// NewServer initializes a new proxy server with the provided configuration.
func NewServer(cfg *config.Config) *Server {
	if cfg == nil {
		cfg = config.DefaultConfig()
	}
	return &Server{
		cfg: cfg,
	}
}

// Start begins listening on the configured address.
func (s *Server) Start(ctx context.Context) error {
	ln, err := net.Listen("tcp", s.cfg.ListenAddr)
	if err != nil {
		return fmt.Errorf("listen on %s failed: %w", s.cfg.ListenAddr, err)
	}

	s.mu.Lock()
	s.listener = ln
	s.mu.Unlock()

	log.Printf("[Proxy] Listening on %s (Fake TLS: %t, SNI: %s)",
		s.cfg.ListenAddr, s.cfg.IsFakeTLS, s.cfg.FakeTLSDomain)

	// Close listener on context cancellation
	go func() {
		<-ctx.Done()
		s.mu.Lock()
		s.closed = true
		if s.listener != nil {
			_ = s.listener.Close()
		}
		s.mu.Unlock()
	}()

	for {
		conn, err := ln.Accept()
		if err != nil {
			s.mu.Lock()
			closed := s.closed
			s.mu.Unlock()
			if closed {
				return nil
			}
			log.Printf("[Proxy] Accept error: %v", err)
			continue
		}

		go s.handleClient(ctx, conn)
	}
}

// Close terminates the proxy server listener.
func (s *Server) Close() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.closed = true
	if s.listener != nil {
		return s.listener.Close()
	}
	return nil
}

func (s *Server) handleClient(ctx context.Context, rawConn net.Conn) {
	relay.GlobalStats.IncConnectionsTotal()
	relay.GlobalStats.IncConnectionsActive()
	defer relay.GlobalStats.DecConnectionsActive()
	defer rawConn.Close()

	peer := rawConn.RemoteAddr().String()
	if tc, ok := rawConn.(*net.TCPConn); ok {
		_ = tc.SetNoDelay(true)
		_ = tc.SetReadBuffer(s.cfg.BufferSize)
		_ = tc.SetWriteBuffer(s.cfg.BufferSize)
	}

	// Read initial byte
	var firstByte [1]byte
	_ = rawConn.SetDeadline(time.Now().Add(s.cfg.Timeout))
	if _, err := io.ReadFull(rawConn, firstByte[:]); err != nil {
		return
	}

	var clientConn net.Conn = rawConn
	handshake := make([]byte, proto.HandshakeLen)

	if firstByte[0] == faketls.TLSRecordHandshake && s.cfg.IsFakeTLS {
		// Fake TLS ClientHello flow
		var hdrRest [4]byte
		if _, err := io.ReadFull(rawConn, hdrRest[:]); err != nil {
			return
		}
		recordLen := binary.BigEndian.Uint16(hdrRest[2:4])
		recordBody := make([]byte, recordLen)
		if _, err := io.ReadFull(rawConn, recordBody); err != nil {
			return
		}

		clientHello := make([]byte, 5+recordLen)
		clientHello[0] = firstByte[0]
		copy(clientHello[1:5], hdrRest[:])
		copy(clientHello[5:], recordBody)

		info, err := faketls.VerifyClientHello(clientHello, s.cfg.SecretBytes)
		if err != nil {
			log.Printf("[%s] Fake TLS verify failed: %v -> redirecting", peer, err)
			redirect := fmt.Sprintf("HTTP/1.1 301 Moved Permanently\r\nLocation: https://%s/\r\nContent-Length: 0\r\nConnection: close\r\n\r\n", s.cfg.FakeTLSDomain)
			_, _ = rawConn.Write([]byte(redirect))
			return
		}

		serverHello, err := faketls.BuildServerHello(s.cfg.SecretBytes, info.ClientRandom, info.SessionID)
		if err != nil {
			log.Printf("[%s] BuildServerHello failed: %v", peer, err)
			return
		}

		if _, err := rawConn.Write(serverHello); err != nil {
			return
		}

		fakeConn := faketls.NewFakeTLSConn(rawConn)
		clientConn = fakeConn

		_ = rawConn.SetDeadline(time.Now().Add(s.cfg.Timeout))
		if _, err := io.ReadFull(fakeConn, handshake); err != nil {
			log.Printf("[%s] Failed to read MTProto init inside TLS: %v", peer, err)
			return
		}
	} else if s.cfg.IsFakeTLS {
		// Client connected with non-TLS byte while Fake TLS is enforced
		redirect := fmt.Sprintf("HTTP/1.1 301 Moved Permanently\r\nLocation: https://%s/\r\nContent-Length: 0\r\nConnection: close\r\n\r\n", s.cfg.FakeTLSDomain)
		_, _ = rawConn.Write([]byte(redirect))
		return
	} else {
		// Raw MTProto
		handshake[0] = firstByte[0]
		if _, err := io.ReadFull(rawConn, handshake[1:]); err != nil {
			return
		}
	}

	_ = rawConn.SetDeadline(time.Time{})
	_ = clientConn.SetDeadline(time.Time{})

	// Parse MTProto handshake
	res, err := crypto.TryHandshake(handshake, s.cfg.SecretBytes)
	if err != nil {
		relay.GlobalStats.IncConnectionsBad()
		log.Printf("[%s] Bad handshake: %v", peer, err)
		return
	}

	dcID := res.DCID
	isMedia := res.IsMedia
	if s.cfg.ForceTestDC || dcID >= 10000 {
		if dcID >= 10000 {
			dcID -= 10000
		}
	}

	dcIdx := int16(dcID)
	if isMedia {
		dcIdx = -dcIdx
	}

	relayInit, err := crypto.GenerateRelayInit(res.ProtoTag, dcIdx)
	if err != nil {
		log.Printf("[%s] Failed to generate relay init: %v", peer, err)
		return
	}

	cryptoCtx, err := crypto.NewCryptoContext(res.ClientDecPrekeyIV, s.cfg.SecretBytes, relayInit)
	if err != nil {
		log.Printf("[%s] Failed to derive crypto context: %v", peer, err)
		return
	}

	splitter := proto.NewMsgSplitter(res.ProtoInt)

	// Attempt WebSocket connection using balancer (fast-cached CF pool + direct)
	domains := globalBalancer.GetDomains(dcID, isMedia, s.cfg.FallbackCF)
	var upstreamWS *ws.RawWebSocket
	var activeDomain string
	var lastErr error

	for _, domain := range domains {
		domainTimeout := 3 * time.Second
		if IsDirectDomain(domain) {
			domainTimeout = 1500 * time.Millisecond // fast failover on blocked direct domains
		}
		if s.cfg.Timeout < domainTimeout {
			domainTimeout = s.cfg.Timeout
		}

		wsConn, err := ws.Connect(ws.DialOptions{
			Host:       domain,
			Domain:     domain,
			Path:       proto.WSPath,
			Secure:     true,
			Timeout:    domainTimeout,
			BufferSize: s.cfg.BufferSize,
		})
		if err == nil {
			upstreamWS = wsConn
			activeDomain = domain
			globalBalancer.RecordSuccess(dcID, domain)
			break
		}

		lastErr = err
		if IsDirectDomain(domain) {
			globalBalancer.RecordDirectFailure(dcID)
		}
	}

	if upstreamWS != nil {
		relay.GlobalStats.IncConnectionsWS()
		log.Printf("[%s] DC%d (media=%t) connected via WS -> %s", peer, dcID, isMedia, activeDomain)
		if err := upstreamWS.Send(relayInit); err != nil {
			_ = upstreamWS.Close()
			return
		}
		relay.BridgeWS(ctx, clientConn, upstreamWS, cryptoCtx, splitter, relay.GlobalStats, dcID, isMedia)
		return
	}

	// Fallback to Cloudflare Worker if direct and CF pool are unavailable
	if s.cfg.FallbackCF && len(s.cfg.CFWorkerDomains) > 0 {
		defaultIP := proto.DCDefaultIPs[dcID]
		workerTimeout := 4 * time.Second
		if s.cfg.Timeout < workerTimeout {
			workerTimeout = s.cfg.Timeout
		}
		for _, workerDomain := range s.cfg.CFWorkerDomains {
			workerPath := fmt.Sprintf("/apiws?dst=%s&dc=%d", defaultIP, dcID)
			wsConn, err := ws.Connect(ws.DialOptions{
				Host:       workerDomain,
				Domain:     workerDomain,
				Path:       workerPath,
				Secure:     true,
				Timeout:    workerTimeout,
				BufferSize: s.cfg.BufferSize,
			})
			if err == nil {
				relay.GlobalStats.IncConnectionsFallback()
				log.Printf("[%s] Connected via CF Worker %s for DC%d", peer, workerDomain, dcID)
				if err := wsConn.Send(relayInit); err != nil {
					_ = wsConn.Close()
					return
				}
				relay.BridgeWS(ctx, clientConn, wsConn, cryptoCtx, splitter, relay.GlobalStats, dcID, isMedia)
				return
			} else {
				lastErr = err
			}
		}
	}

	log.Printf("[%s] DC%d (media=%t): No upstream gateways reachable (last error: %v)", peer, dcID, isMedia, lastErr)
}
