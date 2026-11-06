package engine

import (
	"regexp"
	"strings"
	"sync"
	"time"
)

// logRing keeps a bounded, redacted log tail exposed to the UI via
// {"cmd":"log.tail"}. Tokens never survive in it.
const logRingSize = 256

var (
	logMu   sync.Mutex
	logRing = make([]logLine, 0, logRingSize)
)

type logLine struct {
	At   time.Time `json:"at"`
	Lvl  string    `json:"lvl"`
	Text string    `json:"text"`
}

var (
	jwtLike     = regexp.MustCompile(`eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]{4,}`)
	jwtLikeBare = regexp.MustCompile(`eyJ[A-Za-z0-9_-]{16,}`)
	bearerLike  = regexp.MustCompile(`(?i)(bearer|token)\s+([A-Za-z0-9._-]{12,})`)
)

// Redact removes credential material from a log line. Used by both the
// engine log ring and (via binding) available for Kotlin-side reuse.
func Redact(s string) string {
	s = jwtLike.ReplaceAllString(s, "eyJ…[redacted]")
	s = jwtLikeBare.ReplaceAllString(s, "eyJ…[redacted]")
	s = bearerLike.ReplaceAllString(s, "$1 [redacted]")
	return s
}

func appendLog(level, text string) {
	logMu.Lock()
	defer logMu.Unlock()
	line := logLine{At: time.Now(), Lvl: level, Text: Redact(text)}
	if len(logRing) >= logRingSize {
		copy(logRing, logRing[1:])
		logRing[len(logRing)-1] = line
	} else {
		logRing = append(logRing, line)
	}
}

func tailLogs(n int) []logLine {
	logMu.Lock()
	defer logMu.Unlock()
	if n > len(logRing) {
		n = len(logRing)
	}
	out := make([]logLine, n)
	copy(out, logRing[len(logRing)-n:])
	return out
}

// redactTokenHelp is intentionally unused; documents the policy in one place:
// tokens are passed to cloudflared via environment only and redacted in logs.
var _ = strings.TrimSpace
