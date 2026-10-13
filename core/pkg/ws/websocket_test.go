package ws

import (
	"bytes"
	"crypto/rand"
	"fmt"
	"net"
	"testing"
	"time"
)

func TestWebSocketHandshakeAndFrames(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()

	port := ln.Addr().(*net.TCPAddr).Port

	// Simulated server
	go func() {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		defer conn.Close()

		// Read HTTP handshake
		buf := make([]byte, 1024)
		n, err := conn.Read(buf)
		if err != nil {
			return
		}

		reqStr := string(buf[:n])
		if !bytes.Contains(buf[:n], []byte("Upgrade: websocket")) {
			return
		}

		// Respond with 101 Switching Protocols
		resp := "HTTP/1.1 101 Switching Protocols\r\n" +
			"Upgrade: websocket\r\n" +
			"Connection: Upgrade\r\n" +
			"Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n"
		_, _ = conn.Write([]byte(resp))

		_ = reqStr

		// Read client frame (binary masked)
		// Header 2 bytes
		var h [2]byte
		conn.Read(h[:])
		payloadLen := int(h[1] & 0x7F)
		var mask [4]byte
		conn.Read(mask[:])
		payload := make([]byte, payloadLen)
		conn.Read(payload)

		// Unmask
		for i := 0; i < payloadLen; i++ {
			payload[i] ^= mask[i%4]
		}

		// Echo back unmasked binary frame
		reply := make([]byte, 2+payloadLen)
		reply[0] = 0x82 // FIN + Binary
		reply[1] = byte(payloadLen)
		copy(reply[2:], payload)
		conn.Write(reply)
	}()

	client, err := Connect(DialOptions{
		Host:    "127.0.0.1",
		Port:    port,
		Domain:  fmt.Sprintf("127.0.0.1:%d", port),
		Secure:  false,
		Timeout: 3 * time.Second,
	})
	if err != nil {
		t.Fatalf("Connect failed: %v", err)
	}
	defer client.Close()

	testData := []byte("Hello, Telegram WS gateway!")
	if err := client.Send(testData); err != nil {
		t.Fatalf("Send failed: %v", err)
	}

	reply, err := client.Recv()
	if err != nil {
		t.Fatalf("Recv failed: %v", err)
	}

	if !bytes.Equal(reply, testData) {
		t.Fatalf("echo mismatch: got %s, want %s", string(reply), string(testData))
	}
}

func TestBuildFrame_RandomSizes(t *testing.T) {
	sizes := []int{10, 125, 126, 1024, 65535, 70000}
	for _, sz := range sizes {
		data := make([]byte, sz)
		rand.Read(data)
		frame := buildFrame(OpBinary, data, true)
		if len(frame) < sz+2 {
			t.Fatalf("frame too short for size %d", sz)
		}
	}
}
