package engine

import (
	"sort"
	"sync"
	"sync/atomic"
	"time"
)

// maxLatencySamples bounds the memory used for percentile computation
// (a fixed ring; oldest samples are dropped).
const maxLatencySamples = 8192

// maxTrackedPaths bounds the per-path counter map; on overflow the least
// requested tracked path is evicted before a new one is admitted.
const maxTrackedPaths = 64

// topPathsReported is how many hot paths the status snapshot reports.
const topPathsReported = 8

// maxTrackedPathLen caps a single tracked path's length.
const maxTrackedPathLen = 128

// Metrics collects honest request telemetry for the dashboard.
// Everything is real, measured in-process; nothing is extrapolated.
type Metrics struct {
	requests atomic.Int64
	bytesOut atomic.Int64
	active   atomic.Int64

	mu        sync.Mutex
	byStatus  map[int]int64
	paths     map[string]int64
	latencies []float64 // milliseconds; ring capped at maxLatencySamples
	started   time.Time
}

// NewMetrics returns a zeroed collector.
func NewMetrics() *Metrics {
	return &Metrics{
		byStatus: make(map[int]int64),
		paths:    make(map[string]int64),
		started:  time.Now(),
	}
}

// ActiveOpen marks a connection entering the serving path.
func (m *Metrics) ActiveOpen() { m.active.Add(1) }

// ActiveClose marks a connection leaving the serving path.
func (m *Metrics) ActiveClose() { m.active.Add(-1) }

// Record accounts for one finished response.
func (m *Metrics) Record(status int, latency time.Duration, bytes int64) {
	m.requests.Add(1)
	m.bytesOut.Add(bytes)
	m.mu.Lock()
	m.byStatus[status]++
	if m.latencies == nil {
		m.latencies = make([]float64, 0, maxLatencySamples)
	}
	if len(m.latencies) >= maxLatencySamples {
		copy(m.latencies, m.latencies[1:])
		m.latencies[len(m.latencies)-1] = float64(latency.Microseconds()) / 1000.0
	} else {
		m.latencies = append(m.latencies, float64(latency.Microseconds())/1000.0)
	}
	m.mu.Unlock()
}

// NotePath counts one request toward a URL path. The map is bounded: when it
// is full and a brand-new path arrives, the least-requested tracked path is
// evicted first, so long-tail scanners cannot grow memory without limit.
func (m *Metrics) NotePath(p string) {
	if len(p) > maxTrackedPathLen {
		p = p[:maxTrackedPathLen]
	}
	m.mu.Lock()
	if m.paths == nil {
		m.paths = make(map[string]int64)
	}
	if _, exists := m.paths[p]; !exists && len(m.paths) >= maxTrackedPaths {
		var minKey string
		var minVal int64 = -1
		for k, v := range m.paths {
			if minVal < 0 || v < minVal {
				minKey, minVal = k, v
			}
		}
		if minKey != "" {
			delete(m.paths, minKey)
		}
	}
	m.paths[p]++
	m.mu.Unlock()
}

// PathCount is one entry of the top-requested-paths report.
type PathCount struct {
	Path  string `json:"path"`
	Count int64  `json:"count"`
}

// TopPaths returns up to n most-requested paths, hottest first (ties broken
// alphabetically for deterministic output).
func (m *Metrics) TopPaths(n int) []PathCount {
	m.mu.Lock()
	out := make([]PathCount, 0, len(m.paths))
	for k, v := range m.paths {
		out = append(out, PathCount{Path: k, Count: v})
	}
	m.mu.Unlock()
	sort.Slice(out, func(i, j int) bool {
		if out[i].Count != out[j].Count {
			return out[i].Count > out[j].Count
		}
		return out[i].Path < out[j].Path
	})
	if len(out) > n {
		out = out[:n]
	}
	return out
}

// MetricsSnapshot is the JSON-friendly view of the counters.
type MetricsSnapshot struct {
	Requests  int64          `json:"requests"`
	Active    int64          `json:"active"`
	BytesOut  int64          `json:"bytesOut"`
	ByStatus  map[string]int `json:"byStatus"`
	P50Ms     float64        `json:"p50Ms"`
	P95Ms     float64        `json:"p95Ms"`
	MaxMs     float64        `json:"maxMs"`
	UptimeSec float64        `json:"uptimeSec"`
}

// Snapshot computes percentiles from a single sorted copy of the sample set,
// which guarantees p50 ≤ p95 ≤ max by construction.
func (m *Metrics) Snapshot() map[string]any {
	m.mu.Lock()
	status := make(map[string]int, len(m.byStatus))
	for k, v := range m.byStatus {
		status[fmtInt(k)] = int(v)
	}
	sorted := make([]float64, len(m.latencies))
	copy(sorted, m.latencies)
	m.mu.Unlock()

	sort.Float64s(sorted)
	p50, p95, maxMs := percentiles(sorted)
	// Invariant (defence in depth — cheap to re-assert):
	if !(p50 <= p95+1e-9 && p95 <= maxMs+1e-9) {
		p50, p95, maxMs = 0, 0, 0
	}
	return map[string]any{
		"requests":  m.requests.Load(),
		"active":    m.active.Load(),
		"bytesOut":  m.bytesOut.Load(),
		"byStatus":  status,
		"topPaths":  m.TopPaths(topPathsReported),
		"p50Ms":     p50,
		"p95Ms":     p95,
		"maxMs":     maxMs,
		"uptimeSec": time.Since(m.started).Seconds(),
	}
}

// Reset zeroes every counter and sample.
func (m *Metrics) Reset() {
	m.requests.Store(0)
	m.bytesOut.Store(0)
	m.mu.Lock()
	m.byStatus = make(map[int]int64)
	m.paths = make(map[string]int64)
	m.latencies = m.latencies[:0]
	m.mu.Unlock()
}

func percentiles(sorted []float64) (p50, p95, maxMs float64) {
	n := len(sorted)
	if n == 0 {
		return 0, 0, 0
	}
	p50 = sorted[(n-1)/2]
	p95 = sorted[int(float64(n-1)*0.95)]
	maxMs = sorted[n-1]
	return
}

func fmtInt(v int) string {
	b := [12]byte{}
	pos := len(b)
	if v == 0 {
		return "0"
	}
	neg := v < 0
	if neg {
		v = -v
	}
	for v > 0 {
		pos--
		b[pos] = byte('0' + v%10)
		v /= 10
	}
	if neg {
		pos--
		b[pos] = '-'
	}
	return string(b[pos:])
}
