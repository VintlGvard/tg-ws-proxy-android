package faketls

import (
	"bytes"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"io"
	"net"
	"testing"
	"time"
)

func generateSimulatedClientHello(secret []byte, ts time.Time) []byte {
	ch := make([]byte, 512)
	rand.Read(ch)

	// TLS Record Header
	ch[0] = TLSRecordHandshake
	ch[1] = 0x03
	ch[2] = 0x01
	binary.BigEndian.PutUint16(ch[3:5], uint16(len(ch)-5))

	// Handshake Header
	ch[5] = 0x01 // ClientHello
	ch[6] = 0x00
	binary.BigEndian.PutUint16(ch[7:9], uint16(len(ch)-9))
	ch[9] = 0x03
	ch[10] = 0x03 // TLS 1.2

	ch[43] = 0x20 // Session ID length 32

	// Zero out client random (offset 11..43)
	for i := 0; i < 32; i++ {
		ch[ClientRandomOffset+i] = 0
	}

	mac := hmac.New(sha256.New, secret)
	mac.Write(ch)
	expected := mac.Sum(nil)

	// Fill first 28 bytes
	copy(ch[ClientRandomOffset:ClientRandomOffset+28], expected[:28])

	// XOR timestamp with expected[28:32]
	var tsBytes [4]byte
	binary.LittleEndian.PutUint32(tsBytes[:], uint32(ts.Unix()))
	for i := 0; i < 4; i++ {
		ch[ClientRandomOffset+28+i] = tsBytes[i] ^ expected[28+i]
	}

	return ch
}

func TestVerifyClientHello_Valid(t *testing.T) {
	secret := []byte("0123456789abcdef0123456789abcdef")
	ch := generateSimulatedClientHello(secret, time.Now())

	info, err := VerifyClientHello(ch, secret)
	if err != nil {
		t.Fatalf("expected valid ClientHello, got err: %v", err)
	}
	if info == nil {
		t.Fatalf("expected info, got nil")
	}
	if len(info.SessionID) != 32 {
		t.Fatalf("expected 32-byte session ID")
	}
}

func TestVerifyClientHello_WrongSecret(t *testing.T) {
	secret := []byte("0123456789abcdef0123456789abcdef")
	wrongSecret := []byte("wrong_secret_1234567890123456789")
	ch := generateSimulatedClientHello(secret, time.Now())

	_, err := VerifyClientHello(ch, wrongSecret)
	if err == nil {
		t.Fatalf("expected error for wrong secret, got nil")
	}
}

func TestVerifyClientHello_ExpiredTimestamp(t *testing.T) {
	secret := []byte("0123456789abcdef0123456789abcdef")
	ch := generateSimulatedClientHello(secret, time.Now().Add(-10*time.Minute))

	_, err := VerifyClientHello(ch, secret)
	if err == nil {
		t.Fatalf("expected error for expired timestamp, got nil")
	}
}

func TestBuildServerHello(t *testing.T) {
	secret := []byte("0123456789abcdef0123456789abcdef")
	clientRandom := make([]byte, 32)
	sessionID := make([]byte, 32)
	rand.Read(clientRandom)
	rand.Read(sessionID)

	sh, err := BuildServerHello(secret, clientRandom, sessionID)
	if err != nil {
		t.Fatalf("BuildServerHello failed: %v", err)
	}

	if len(sh) < 100 {
		t.Fatalf("ServerHello too short: %d", len(sh))
	}
	if sh[0] != TLSRecordHandshake {
		t.Fatalf("invalid record type: %02x", sh[0])
	}
}

func TestFakeTLSConn_Pipe(t *testing.T) {
	clientPipe, serverPipe := net.Pipe()
	defer clientPipe.Close()
	defer serverPipe.Close()

	tlsServer := NewFakeTLSConn(serverPipe)

	msg := []byte("Test Fake TLS stream tunneling")

	go func() {
		tlsServer.Write(msg)
	}()

	// The raw client pipe receives TLS record 0x17 0x03 0x03 [len] [msg]
	var hdr [5]byte
	io.ReadFull(clientPipe, hdr[:])
	if hdr[0] != TLSRecordAppData {
		t.Fatalf("expected appdata record, got %02x", hdr[0])
	}

	recordLen := binary.BigEndian.Uint16(hdr[3:5])
	if int(recordLen) != len(msg) {
		t.Fatalf("expected record len %d, got %d", len(msg), recordLen)
	}

	payload := make([]byte, recordLen)
	io.ReadFull(clientPipe, payload)
	if !bytes.Equal(payload, msg) {
		t.Fatalf("payload mismatch")
	}
}
