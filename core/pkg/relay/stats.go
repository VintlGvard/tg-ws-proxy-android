package relay

import (
	"sync/atomic"
)

// Stats tracks aggregate network statistics across proxy sessions.
// Uses atomic types to guarantee 64-bit alignment across all architectures (including 32-bit ARM).
type Stats struct {
	bytesUp             atomic.Uint64
	bytesDown           atomic.Uint64
	connectionsTotal    atomic.Uint64
	connectionsActive   atomic.Int64
	connectionsBad      atomic.Uint64
	connectionsWS       atomic.Uint64
	connectionsFallback atomic.Uint64
}

// StatsSnapshot holds point-in-time scalar values of Stats.
type StatsSnapshot struct {
	BytesUp             uint64
	BytesDown           uint64
	ConnectionsTotal    uint64
	ConnectionsActive   int64
	ConnectionsBad      uint64
	ConnectionsWS       uint64
	ConnectionsFallback uint64
}

var GlobalStats = &Stats{}

func (s *Stats) AddBytesUp(n int) {
	s.bytesUp.Add(uint64(n))
}

func (s *Stats) AddBytesDown(n int) {
	s.bytesDown.Add(uint64(n))
}

func (s *Stats) IncConnectionsTotal() {
	s.connectionsTotal.Add(1)
}

func (s *Stats) IncConnectionsActive() {
	s.connectionsActive.Add(1)
}

func (s *Stats) DecConnectionsActive() {
	s.connectionsActive.Add(-1)
}

func (s *Stats) IncConnectionsBad() {
	s.connectionsBad.Add(1)
}

func (s *Stats) IncConnectionsWS() {
	s.connectionsWS.Add(1)
}

func (s *Stats) IncConnectionsFallback() {
	s.connectionsFallback.Add(1)
}

// Snapshot returns a point-in-time copy of current stats.
func (s *Stats) Snapshot() StatsSnapshot {
	return StatsSnapshot{
		BytesUp:             s.bytesUp.Load(),
		BytesDown:           s.bytesDown.Load(),
		ConnectionsTotal:    s.connectionsTotal.Load(),
		ConnectionsActive:   s.connectionsActive.Load(),
		ConnectionsBad:      s.connectionsBad.Load(),
		ConnectionsWS:       s.connectionsWS.Load(),
		ConnectionsFallback: s.connectionsFallback.Load(),
	}
}
