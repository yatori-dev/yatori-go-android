package mobilecore

// blockedReasons maps skipped/blocked platforms to a concise reason string.
// Only includes platforms that are NOT fully implemented in mobilecore.
// Fully-wired platforms (xuexitong, cqie, yinghua, qingshuxuetang, …) are NOT here.
var blockedReasons = map[string]string{
	"weiban":         "blocked: requires OCR/captcha native adapter; no course/task API",
	"qingshuxuetang": "sync Login blocked; use StartLogin for OCR captcha flow",
	"icve":           "blocked: api/icve imports os/exec; aggregation imports ddddocr-go; desktop-only — skipped",
	"mooc":           "blocked: login requires VDF/POW; no task/submit API — skipped",
	"cela":           "blocked: login multi-stage captcha; no course chain — skipped",
	"gongxue":        "blocked: api imports gorm/gomail global — skipped",
}

// isOCRBlocked returns true when the platform is still blocked pending an
// Android OCR/captcha adapter (i.e. StartLogin state machine not yet wired).
func isOCRBlocked(platform string) bool {
	return platform == "weiban"
}

// blockedReason returns the blocked reason for a platform, or a generic message.
func blockedReason(platform string) string {
	if r, ok := blockedReasons[platform]; ok {
		return r
	}
	return "blocked: platform not available in mobilecore"
}
