// Package engine is the Stratos hosting engine: a stdlib-only static HTTP
// server designed to be embedded inside the Android app process via
// `gomobile bind`. The Kotlin layer talks to this package exclusively
// through the JSON command bus exposed by Command — there is deliberately
// no management HTTP surface, no child process and no shell.
//
// Concurrency: every exported entry point is safe to call from any thread.
package engine

// Version is the engine semantic version. Keep in sync with CHANGELOG.md.
const Version = "1.0.0"

// Command executes a single JSON command and returns a single JSON
// response. This is the only control surface of the engine (JNI-only bus,
// mirroring the security model of never exposing management over HTTP).
//
// Requests (subset, see bus.go for the full schema):
//
//	{"cmd":"ping"}
//	{"cmd":"start","port":8080,"bind":"0.0.0.0","sites":[...]}
//	{"cmd":"stop"}
//	{"cmd":"status"}
//	{"cmd":"metrics.reset"}
//	{"cmd":"sites.set","sites":[...]}
//	{"cmd":"log.tail","n":100}
//
// Responses are always an envelope: {"ok":true,"data":{...}} or
// {"ok":false,"error":"human readable reason"}.
func Command(request string) string {
	return dispatch(request)
}

// VersionString returns the engine version (gomobile-friendly export).
func VersionString() string { return Version }
