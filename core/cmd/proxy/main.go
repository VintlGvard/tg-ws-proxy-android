package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"net"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"

	"tg-ws-proxy-core/pkg/config"
	"tg-ws-proxy-core/pkg/proxy"
	"tg-ws-proxy-core/pkg/relay"
)

func main() {
	listenFlag := flag.String("listen", "127.0.0.1:1443", "Address to listen on (e.g. 127.0.0.1:1443)")
	secretFlag := flag.String("secret", "eed41d8cd98f00b204e9800998ecf8427e676f6f676c652e636f6d", "Proxy secret (plain hex or ee<secret><domain>)")
	domainFlag := flag.String("domain", "google.com", "Masking domain for Fake TLS")
	cfWorkerFlag := flag.String("cf-worker", "", "Comma-separated Cloudflare worker domains for fallback")
	noFakeTLSFlag := flag.Bool("no-faketls", false, "Disable Fake TLS (use raw MTProto obfuscation)")
	statsIntervalFlag := flag.Int("stats-interval", 15, "Seconds between stats logs (0 to disable)")

	flag.Parse()

	cfg := config.DefaultConfig()
	cfg.ListenAddr = *listenFlag
	cfg.FakeTLSDomain = *domainFlag
	cfg.IsFakeTLS = !*noFakeTLSFlag

	if *secretFlag != "" {
		if err := cfg.ParseSecret(*secretFlag); err != nil {
			log.Fatalf("Invalid secret provided: %v", err)
		}
	}

	if *cfWorkerFlag != "" {
		for _, w := range strings.Split(*cfWorkerFlag, ",") {
			w = strings.TrimSpace(w)
			if w != "" {
				cfg.CFWorkerDomains = append(cfg.CFWorkerDomains, w)
			}
		}
	}

	host, portStr, err := net.SplitHostPort(cfg.ListenAddr)
	if err != nil {
		host = "127.0.0.1"
		portStr = "1443"
	}
	port, _ := strconv.Atoi(portStr)

	tgLink := cfg.GenerateTelegramProxyLink(host, port)

	fmt.Println("==================================================")
	fmt.Println("   TG WS Proxy (Go Mobile Core)                 ")
	fmt.Println("==================================================")
	fmt.Printf(" Listening on:    %s\n", cfg.ListenAddr)
	fmt.Printf(" Fake TLS:        %t (SNI: %s)\n", cfg.IsFakeTLS, cfg.FakeTLSDomain)
	fmt.Printf(" Telegram Link:   %s\n", tgLink)
	fmt.Println("==================================================")

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	srv := proxy.NewServer(cfg)

	// Periodic stats printer
	if *statsIntervalFlag > 0 {
		go func() {
			ticker := time.NewTicker(time.Duration(*statsIntervalFlag) * time.Second)
			defer ticker.Stop()
			for {
				select {
				case <-ctx.Done():
					return
				case <-ticker.C:
					snap := relay.GlobalStats.Snapshot()
					if snap.ConnectionsTotal > 0 {
						log.Printf("[Stats] Active: %d | Total: %d (WS: %d, FB: %d, Bad: %d) | Up: %.2f MB | Down: %.2f MB",
							snap.ConnectionsActive, snap.ConnectionsTotal,
							snap.ConnectionsWS, snap.ConnectionsFallback, snap.ConnectionsBad,
							float64(snap.BytesUp)/1048576.0, float64(snap.BytesDown)/1048576.0)
					}
				}
			}
		}()
	}

	if err := srv.Start(ctx); err != nil {
		log.Fatalf("Proxy server failed: %v", err)
	}

	log.Println("[Proxy] Server shutdown cleanly.")
}
