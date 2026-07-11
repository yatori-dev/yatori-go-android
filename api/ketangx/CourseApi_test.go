package ketangx

import (
	"errors"
	"io"
	"net/http"
	"strings"
	"testing"
)

type ketangxRoundTripFunc func(*http.Request) (*http.Response, error)

func (f ketangxRoundTripFunc) RoundTrip(req *http.Request) (*http.Response, error) {
	return f(req)
}

func TestProtectedCourseAPIsDetectLoginPageAsSessionExpired(t *testing.T) {
	original := ketangxHTTPClientFactory
	ketangxHTTPClientFactory = func() *http.Client {
		return &http.Client{Transport: ketangxRoundTripFunc(func(req *http.Request) (*http.Response, error) {
			body := `<form action="/Login/AccLogin"><input name="userAccount"><input name="password"></form>`
			return &http.Response{
				StatusCode: http.StatusOK,
				Header:     make(http.Header),
				Body:       io.NopCloser(strings.NewReader(body)),
				Request:    req,
			}, nil
		})}
	}
	defer func() { ketangxHTTPClientFactory = original }()

	cache := &KetangxUserCache{}
	tests := []struct {
		name string
		call func() error
	}{
		{"course-list", func() error { _, err := cache.PullCourseListHTMLApi(); return err }},
		{"node-list", func() error { _, err := cache.PullVideoListHTMLApi("course"); return err }},
		{"sign", func() error { _, err := cache.SignVideoStatusApi("section"); return err }},
		{"complete", func() error { _, err := cache.CompleteVideoApi("section", "id", 1, 1); return err }},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			err := test.call()
			if err == nil || !strings.Contains(err.Error(), "账号登录超时，请重新登录") {
				t.Fatalf("%s login page error=%v, want session-expired marker", test.name, err)
			}
		})
	}
}

func TestProtectedCourseAPIsPropagateTransportErrors(t *testing.T) {
	original := ketangxHTTPClientFactory
	ketangxHTTPClientFactory = func() *http.Client {
		return &http.Client{Transport: ketangxRoundTripFunc(func(*http.Request) (*http.Response, error) {
			return nil, errors.New("network down")
		})}
	}
	defer func() { ketangxHTTPClientFactory = original }()

	cache := &KetangxUserCache{}
	tests := []struct {
		name string
		call func() error
	}{
		{"course-list", func() error { _, err := cache.PullCourseListHTMLApi(); return err }},
		{"node-list", func() error { _, err := cache.PullVideoListHTMLApi("course"); return err }},
		{"sign", func() error { _, err := cache.SignVideoStatusApi("section"); return err }},
		{"complete", func() error { _, err := cache.CompleteVideoApi("section", "id", 1, 1); return err }},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if err := test.call(); err == nil {
				t.Fatalf("%s transport failure must be returned", test.name)
			}
		})
	}
}
