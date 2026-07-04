//go:build android || mobile

package utils

// YatoriCoreInit is a no-op on Android.
// OCR, node_modules, and exe assets are not available on this platform.
func YatoriCoreInit() {}
