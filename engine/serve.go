package engine

import (
	"compress/gzip"
	"fmt"
	"io"
	"io/fs"
	"net/http"
	"os"
	"path"
	"strconv"
	"strings"
	"time"
)

// serveSite resolves rel inside the site's os.Root and streams the file with
// ETag/Last-Modified/304, optional gzip and single-range support.
func (s *Server) serveSite(w http.ResponseWriter, r *http.Request, st *site, rel string) {
	s.metrics.ActiveOpen()
	defer s.metrics.ActiveClose()
	start := time.Now()

	// Normalise and validate the unrooted path. fs.ValidPath rejects "..",
	// leading/trailing slashes and empty elements — this is the primary
	// traversal defence (os.Root is the second).
	clean := path.Clean("/" + rel)
	unrooted := strings.TrimPrefix(clean, "/")
	if unrooted == "" {
		unrooted = "."
	}
	if !fs.ValidPath(unrooted) {
		s.errorStatus(w, r, http.StatusBadRequest, "400 — Bad Request", "Malformed path.")
		return
	}

	f, err := st.root.Open(unrooted)
	if err != nil {
		// SPA fallback: only for GET/HEAD page navigations.
		if st.cfg.SPA && r.Header.Get("Accept") != "" && !strings.Contains(unrooted, ".") {
			s.serveIndexFallback(w, r, st, start)
			return
		}
		s.errorStatus(w, r, http.StatusNotFound, "404 — Not Found", "The page you requested does not exist on this server.")
		return
	}
	defer f.Close()

	info, err := f.Stat()
	if err != nil {
		s.errorStatus(w, r, http.StatusInternalServerError, "500 — Server Error", "Cannot stat the requested file.")
		return
	}

	if info.IsDir() {
		// Directory: resolve index.html / index.htm; redirect bare dirs.
		indexPath := joinPath(unrooted, "index.html")
		iff, err2 := st.root.Open(indexPath)
		if err2 != nil {
			iff, err2 = st.root.Open(joinPath(unrooted, "index.htm"))
			if err2 != nil {
				if st.cfg.SPA {
					s.serveIndexFallback(w, r, st, start)
					return
				}
				s.errorStatus(w, r, http.StatusNotFound, "404 — Not Found", "Directory has no index page.")
				return
			}
		}
		defer iff.Close()
		iinfo, err2 := iff.Stat()
		if err2 != nil || iinfo.IsDir() {
			s.errorStatus(w, r, http.StatusNotFound, "404 — Not Found", "Directory has no index page.")
			return
		}
		if unrooted != "." && !strings.HasSuffix(r.URL.Path, "/") {
			http.Redirect(w, r, r.URL.Path+"/", http.StatusMovedPermanently)
			s.metrics.Record(http.StatusMovedPermanently, time.Since(start), 0)
			return
		}
		s.stream(w, r, iff, iinfo, st, start)
		return
	}

	s.stream(w, r, f, info, st, start)
}

// serveIndexFallback serves the site's /index.html for SPA client routes.
func (s *Server) serveIndexFallback(w http.ResponseWriter, r *http.Request, st *site, start time.Time) {
	idx, err := st.root.Open("index.html")
	if err != nil {
		s.errorStatus(w, r, http.StatusNotFound, "404 — Not Found", "The page you requested does not exist on this server.")
		return
	}
	defer idx.Close()
	info, err := idx.Stat()
	if err != nil || info.IsDir() {
		s.errorStatus(w, r, http.StatusNotFound, "404 — Not Found", "The page you requested does not exist on this server.")
		return
	}
	s.stream(w, r, idx, info, st, start)
}

// stream writes one regular file with caching, compression and range support.
func (s *Server) stream(w http.ResponseWriter, r *http.Request, f *os.File, info os.FileInfo, st *site, start time.Time) {
	size := info.Size()
	mtime := info.ModTime()
	etag := fmt.Sprintf(`"%x-%x"`, size, mtime.Unix())
	w.Header().Set("ETag", etag)
	w.Header().Set("Last-Modified", mtime.UTC().Format(http.TimeFormat))
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("Referrer-Policy", "strict-origin-when-cross-origin")

	ct := mimeByExt(path.Ext(info.Name()))
	w.Header().Set("Content-Type", ct)

	// Cache policy: hashed assets get long-lived caching; HTML stays fresh.
	if ct == "text/html; charset=utf-8" || ct == "application/json" {
		w.Header().Set("Cache-Control", "no-cache")
	} else {
		maxAge := st.cfg.CacheMax
		if maxAge <= 0 {
			maxAge = maxCacheAgeDefault
		}
		w.Header().Set("Cache-Control", fmt.Sprintf("public, max-age=%d", maxAge))
	}

	// Conditional requests.
	if match := r.Header.Get("If-None-Match"); match != "" && etagMatches(match, etag) {
		w.WriteHeader(http.StatusNotModified)
		s.metrics.Record(http.StatusNotModified, time.Since(start), 0)
		return
	}
	if ims := r.Header.Get("If-Modified-Since"); ims != "" {
		if t, err := http.ParseTime(ims); err == nil && !mtime.Truncate(time.Second).After(t) {
			w.WriteHeader(http.StatusNotModified)
			s.metrics.Record(http.StatusNotModified, time.Since(start), 0)
			return
		}
	}

	if r.Method == http.MethodHead {
		w.Header().Set("Content-Length", strconv.FormatInt(size, 10))
		w.WriteHeader(http.StatusOK)
		s.metrics.Record(http.StatusOK, time.Since(start), 0)
		return
	}

	// Single byte-range support (audio/video seeking).
	if rng := r.Header.Get("Range"); rng != "" {
		if start_, end_, ok := parseRange(rng, size); ok {
			if _, err := f.Seek(start_, io.SeekStart); err == nil {
				length := end_ - start_ + 1
				w.Header().Set("Content-Range",
					fmt.Sprintf("bytes %d-%d/%d", start_, end_, size))
				w.Header().Set("Content-Length", strconv.FormatInt(length, 10))
				w.Header().Set("Accept-Ranges", "bytes")
				w.WriteHeader(http.StatusPartialContent)
				n, _ := io.CopyN(w, f, length)
				s.metrics.Record(http.StatusPartialContent, time.Since(start), n)
				return
			}
		}
		// Invalid range → full content per RFC 7233.
	}

	w.Header().Set("Accept-Ranges", "bytes")

	// gzip for compressible bodies when the client accepts it.
	var out io.Writer = w
	sentHeaders := false
	if acceptsGzip(r) && compressible(ct) && size >= 200 && size <= 10<<20 {
		w.Header().Set("Content-Encoding", "gzip")
		w.Header().Del("Content-Length")
		w.WriteHeader(http.StatusOK)
		sentHeaders = true
		gz := gzip.NewWriter(w)
		defer gz.Close()
		out = gz
	}

	if !sentHeaders {
		w.Header().Set("Content-Length", strconv.FormatInt(size, 10))
		w.WriteHeader(http.StatusOK)
	}
	n, err := io.Copy(out, f)
	if err != nil {
		appendLog("WARN", "copy: "+err.Error())
	}
	s.metrics.Record(http.StatusOK, time.Since(start), n)
}

func (s *Server) errorStatus(w http.ResponseWriter, r *http.Request, code int, title, msg string) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.WriteHeader(code)
	if r.Method != http.MethodHead {
		_, _ = w.Write([]byte(errorPage(code, title, msg)))
	}
	s.metrics.Record(code, 0, 0)
}

// ---- helpers -------------------------------------------------------------

func joinPath(dir, name string) string {
	if dir == "." || dir == "" {
		return name
	}
	return dir + "/" + name
}

// etagMatches performs a weak compare over a comma-separated If-None-Match.
func etagMatches(header, etag string) bool {
	if header == "" {
		return false
	}
	if header == "*" {
		return true
	}
	for _, part := range strings.Split(header, ",") {
		part = strings.TrimSpace(part)
		part = strings.TrimPrefix(part, "W/")
		etagClean := strings.TrimPrefix(etag, "W/")
		if part == etagClean {
			return true
		}
	}
	return false
}

// parseRange parses a single "bytes=a-b" range. Returns ok=false for
// multi-range, suffix-open, invalid or unsatisfiable ranges (then the whole
// body is sent, per RFC 7233 fallback behaviour).
func parseRange(header string, size int64) (start, end int64, ok bool) {
	if size <= 0 || !strings.HasPrefix(header, "bytes=") {
		return 0, 0, false
	}
	spec := strings.TrimPrefix(header, "bytes=")
	if strings.Contains(spec, ",") {
		return 0, 0, false
	}
	dash := strings.IndexByte(spec, '-')
	if dash < 0 {
		return 0, 0, false
	}
	a, b := strings.TrimSpace(spec[:dash]), strings.TrimSpace(spec[dash+1:])
	if a == "" {
		// suffix range: last N bytes — not needed for static pages; skip.
		return 0, 0, false
	}
	start, err := strconv.ParseInt(a, 10, 64)
	if err != nil || start < 0 || start >= size {
		return 0, 0, false
	}
	if b == "" {
		end = size - 1
	} else {
		end, err = strconv.ParseInt(b, 10, 64)
		if err != nil || end < start {
			return 0, 0, false
		}
		if end >= size {
			end = size - 1
		}
	}
	return start, end, true
}

func acceptsGzip(r *http.Request) bool {
	ae := r.Header.Get("Accept-Encoding")
	return strings.Contains(ae, "gzip")
}

func compressible(ct string) bool {
	for _, p := range []string{"text/", "application/json", "application/javascript",
		"application/xml", "image/svg+xml", "application/wasm", "application/manifest+json"} {
		if strings.HasPrefix(ct, p) {
			return true
		}
	}
	return false
}

var mimeTable = map[string]string{
	".html": "text/html; charset=utf-8", ".htm": "text/html; charset=utf-8",
	".css": "text/css; charset=utf-8", ".js": "text/javascript; charset=utf-8",
	".mjs": "text/javascript; charset=utf-8", ".json": "application/json",
	".map": "application/json", ".txt": "text/plain; charset=utf-8",
	".md": "text/markdown; charset=utf-8", ".xml": "application/xml",
	".svg": "image/svg+xml", ".png": "image/png", ".jpg": "image/jpeg",
	".jpeg": "image/jpeg", ".gif": "image/gif", ".webp": "image/webp",
	".avif": "image/avif", ".ico": "image/x-icon", ".bmp": "image/bmp",
	".woff": "font/woff", ".woff2": "font/woff2", ".ttf": "font/ttf",
	".otf": "font/otf", ".eot": "application/vnd.ms-fontobject",
	".mp4": "video/mp4", ".webm": "video/webm", ".mov": "video/quicktime",
	".mp3": "audio/mpeg", ".wav": "audio/wav", ".ogg": "audio/ogg",
	".m4a": "audio/mp4", ".pdf": "application/pdf", ".zip": "application/zip",
	".wasm": "application/wasm", ".webmanifest": "application/manifest+json",
	".csv": "text/csv; charset=utf-8", ".ics": "text/calendar",
	".rss": "application/rss+xml", ".atom": "application/atom+xml",
}

func mimeByExt(ext string) string {
	if m, ok := mimeTable[strings.ToLower(ext)]; ok {
		return m
	}
	return "application/octet-stream"
}
