package engine

import (
	"net/http"
	"time"
)

// Live access log: a bounded, per-server ring of recently served requests,
// exposed to the UI via {"cmd":"log.access"}. Policy mirrors the top-path
// counters — requests rejected by the credential wall (401) are never
// recorded, so Basic-auth negotiation doesn't double-count each visit and
// scanners can't flood the tail. Paths are truncated and credential-redacted
// before they enter the ring; tokens never survive.
const (
	accessRingSize = 128
	accessPathCap  = 128
)

// accessEntry is one served request on the wire.
type accessEntry struct {
	At     int64  `json:"at"` // unix millis
	Method string `json:"method"`
	Path   string `json:"path"`
	Status int    `json:"status"`
	Ms     int64  `json:"ms"`
	Bytes  int64  `json:"bytes,omitempty"`
}

// redactPath caps a path's length and strips credential material.
func redactPath(p string) string {
	if len(p) > accessPathCap {
		p = p[:accessPathCap]
	}
	return Redact(p)
}

// noteAccess appends one served request, evicting the oldest entry past the cap.
func (s *Server) noteAccess(e accessEntry) {
	s.accessMu.Lock()
	defer s.accessMu.Unlock()
	if len(s.access) >= accessRingSize {
		copy(s.access, s.access[1:])
		s.access[len(s.access)-1] = e
	} else {
		s.access = append(s.access, e)
	}
}

// TailAccess returns up to n recent entries, newest first.
func (s *Server) TailAccess(n int) []accessEntry {
	s.accessMu.Lock()
	defer s.accessMu.Unlock()
	if n > len(s.access) {
		n = len(s.access)
	}
	out := make([]accessEntry, 0, n)
	for i := len(s.access) - 1; i >= len(s.access)-n; i-- {
		out = append(out, s.access[i])
	}
	return out
}

// resetAccess empties the ring (part of the metrics reset surface).
func (s *Server) resetAccess() {
	s.accessMu.Lock()
	s.access = s.access[:0]
	s.accessMu.Unlock()
}

// statusWriter captures the response code and byte count for the access log
// while passing every call through to the real writer. Unwrap keeps
// http.ResponseController working over the wrapper.
type statusWriter struct {
	http.ResponseWriter
	status int
	bytes  int64
}

func (w *statusWriter) Unwrap() http.ResponseWriter { return w.ResponseWriter }

func (w *statusWriter) WriteHeader(code int) {
	if w.status == 0 {
		w.status = code
	}
	w.ResponseWriter.WriteHeader(code)
}

func (w *statusWriter) Write(b []byte) (int, error) {
	if w.status == 0 {
		w.status = http.StatusOK
	}
	n, err := w.ResponseWriter.Write(b)
	w.bytes += int64(n)
	return n, err
}

// wallClock exists so tests and callers stay on one time source.
func wallClock() int64 { return time.Now().UnixMilli() }
