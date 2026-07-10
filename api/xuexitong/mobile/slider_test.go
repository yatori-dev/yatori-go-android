package mobile

import (
	"image"
	"image/color"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestSliderGetRefererHeader(t *testing.T) {
	const referer = "https://mooc1-api.chaoxing.com/exam-ans/exam/phone/task-exam?taskrefId=exam-1"
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if got := req.Referer(); got != referer {
			t.Errorf("Referer = %q, want %q", got, referer)
		}
		if got := req.Header.Get("X-Requested-With"); got != "com.chaoxing.mobile" {
			t.Errorf("X-Requested-With = %q", got)
		}
		_, _ = io.WriteString(w, "ok")
	}))
	defer server.Close()

	got, err := (&XxtClient{}).sliderGet(server.URL, referer)
	if err != nil {
		t.Fatalf("sliderGet: %v", err)
	}
	if got != "ok" {
		t.Fatalf("body = %q, want ok", got)
	}
}

func TestSliderGetOmitsEmptyReferer(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if got := req.Header.Get("Referer"); got != "" {
			t.Errorf("Referer = %q, want empty", got)
		}
		_, _ = io.WriteString(w, "ok")
	}))
	defer server.Close()

	if _, err := (&XxtClient{}).sliderGet(server.URL, ""); err != nil {
		t.Fatalf("sliderGet: %v", err)
	}
}
func TestDetectSlideOffsetCropsBlackPadding(t *testing.T) {
	const (
		cutoutTop = 38
		matchX    = 173
	)
	background := image.NewRGBA(image.Rect(0, 0, 320, 160))
	for y := 0; y < 160; y++ {
		for x := 0; x < 320; x++ {
			value := uint8((x*17+y*31+(x*y)%251)%220 + 20)
			background.SetRGBA(x, y, color.RGBA{R: value, G: value, B: value, A: 255})
		}
	}

	cutout := image.NewRGBA(image.Rect(0, 0, 56, 160))
	// A visible top edge lets the pure-Go contour approximation locate the
	// piece; the interior is copied from the matching background location.
	for x := 5; x < 53; x++ {
		cutout.SetRGBA(x, cutoutTop, color.RGBA{R: 128, G: 128, B: 128, A: 255})
	}
	for y := cutoutTop + 2; y < cutoutTop+44; y++ {
		for x := 8; x < 48; x++ {
			cutout.Set(x, y, background.At(matchX+x-8, y))
		}
	}

	if got, want := DetectSlideOffset(background, cutout), matchX-5; got != want {
		t.Fatalf("DetectSlideOffset = %d, want %d", got, want)
	}
}
