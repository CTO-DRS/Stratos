package engine

import (
	"encoding/json"
	"fmt"
	"strings"
	"sync"
)

// siteConfigJSON is the wire format of a site on the command bus.
type siteConfigJSON struct {
	ID       string `json:"id"`
	Name     string `json:"name,omitempty"`
	Root     string `json:"root"`
	Default  bool   `json:"default,omitempty"`
	Enabled  bool   `json:"enabled,omitempty"`
	SPA      bool   `json:"spa,omitempty"`
	CacheMax int    `json:"cacheMax,omitempty"` // seconds for immutable assets; 0 = default policy
}

type busRequest struct {
	Cmd   string            `json:"cmd"`
	Port  int               `json:"port,omitempty"`
	Bind  string            `json:"bind,omitempty"` // host: "" (all), "0.0.0.0", "127.0.0.1" or a LAN IP
	Sites []siteConfigJSON  `json:"sites,omitempty"`
	N     int               `json:"n,omitempty"`
	Extra map[string]string `json:"extra,omitempty"`
}

type busResponse struct {
	OK   bool   `json:"ok"`
	Data any    `json:"data,omitempty"`
	Err  string `json:"error,omitempty"`
}

func okResp(data any) string { return marshal(busResponse{OK: true, Data: data}) }
func errResp(err error) string {
	return marshal(busResponse{OK: false, Err: err.Error()})
}

func marshal(v any) string {
	b, err := json.Marshal(v)
	if err != nil {
		return `{"ok":false,"error":"marshal failure"}`
	}
	return string(b)
}

// engineMu serialises lifecycle commands (start/stop) while allowing
// concurrent status/metrics reads through the Server's own locks.
var engineMu sync.Mutex

var activeServer *Server

// dispatch routes one JSON command. Always returns a JSON envelope string.
func dispatch(request string) string {
	var req busRequest
	if err := json.Unmarshal([]byte(request), &req); err != nil {
		return errResp(fmt.Errorf("bad request json: %v", err))
	}
	switch req.Cmd {
	case "ping":
		return okResp(map[string]any{"version": Version, "engine": "go"})
	case "start":
		engineMu.Lock()
		defer engineMu.Unlock()
		if activeServer != nil {
			// Idempotent restart: stop the previous instance first.
			_ = activeServer.Stop()
			activeServer = nil
		}
		srv, err := NewServer(req.Bind, req.Port, fromWire(req.Sites))
		if err != nil {
			return errResp(err)
		}
		if err := srv.Start(); err != nil {
			return errResp(err)
		}
		activeServer = srv
		return okResp(map[string]any{"addr": srv.Addr()})
	case "stop":
		engineMu.Lock()
		defer engineMu.Unlock()
		if activeServer == nil {
			return okResp(map[string]any{"running": false})
		}
		err := activeServer.Stop()
		activeServer = nil
		if err != nil {
			return errResp(err)
		}
		return okResp(map[string]any{"running": false})
	case "status":
		engineMu.Lock()
		srv := activeServer
		engineMu.Unlock()
		if srv == nil {
			return okResp(map[string]any{"running": false})
		}
		return okResp(srv.Snapshot())
	case "sites.set":
		engineMu.Lock()
		srv := activeServer
		engineMu.Unlock()
		if srv == nil {
			return errResp(fmt.Errorf("engine not running"))
		}
		srv.SetSites(fromWire(req.Sites))
		return okResp(map[string]any{"reloaded": len(req.Sites)})
	case "metrics.reset":
		engineMu.Lock()
		srv := activeServer
		engineMu.Unlock()
		if srv != nil {
			srv.ResetMetrics()
		}
		return okResp(map[string]any{"reset": true})
	case "zip.extract":
		zipPath, _ := req.Extra["zipPath"]
		destDir, _ := req.Extra["destDir"]
		if zipPath == "" || destDir == "" {
			return errResp(fmt.Errorf("zipPath and destDir are required"))
		}
		res, err := ValidateAndExtract(zipPath, destDir)
		if err != nil {
			return errResp(err)
		}
		return okResp(map[string]any{
			"files": res.Files, "dirs": res.Dirs, "bytes": res.Bytes,
			"root": res.Root, "hasIndex": res.HasIndex, "entryCount": res.EntryCount,
		})
	case "log.tail":
		n := req.N
		if n <= 0 || n > 500 {
			n = 100
		}
		return okResp(map[string]any{"lines": tailLogs(n)})
	default:
		return errResp(fmt.Errorf("unknown command %q", req.Cmd))
	}
}

func fromWire(in []siteConfigJSON) []*SiteConfig {
	out := make([]*SiteConfig, 0, len(in))
	for _, s := range in {
		if strings.TrimSpace(s.ID) == "" || strings.TrimSpace(s.Root) == "" {
			continue
		}
		out = append(out, &SiteConfig{
			ID:       s.ID,
			Name:     s.Name,
			Root:     s.Root,
			Default:  s.Default,
			Enabled:  s.Enabled,
			SPA:      s.SPA,
			CacheMax: s.CacheMax,
		})
	}
	return out
}
