package cqie

import (
	"errors"
	"io"
	"net/http"
	"strings"
	"testing"
	"time"
)

type cqieRoundTripFunc func(*http.Request) (*http.Response, error)

func (f cqieRoundTripFunc) RoundTrip(req *http.Request) (*http.Response, error) {
	return f(req)
}

func installCqieCourseHTTPClient(t *testing.T, roundTrip cqieRoundTripFunc) {
	t.Helper()
	oldFactory := cqieCourseHTTPClientFactory
	client := &http.Client{Transport: roundTrip}
	cqieCourseHTTPClientFactory = func() *http.Client { return client }
	t.Cleanup(func() { cqieCourseHTTPClientFactory = oldFactory })
}

func cqieOKResponse() *http.Response {
	return &http.Response{
		StatusCode: http.StatusOK,
		Header:     make(http.Header),
		Body:       io.NopCloser(strings.NewReader(`{"ok":true}`)),
	}
}

func TestPullCourseListApiNewRetryUsesFrequentEndpoint(t *testing.T) {
	var paths []string
	installCqieCourseHTTPClient(t, func(req *http.Request) (*http.Response, error) {
		paths = append(paths, req.URL.Path)
		if len(paths) == 1 {
			return nil, errors.New("temporary network failure")
		}
		return cqieOKResponse(), nil
	})

	cache := &CqieUserCache{studentId: "student", orgMajorId: "major"}
	if _, err := cache.PullCourseListApiNew(1, nil); err != nil {
		t.Fatalf("PullCourseListApiNew returned error: %v", err)
	}

	want := "/gateway/frequent/orgStudent/pagedMyCourse"
	if len(paths) != 2 || paths[0] != want || paths[1] != want {
		t.Fatalf("retry paths = %#v, want [%q %q]", paths, want, want)
	}
}

func TestSubmitStudyTimeApiRetryPreservesCoursewareID(t *testing.T) {
	var bodies []string
	installCqieCourseHTTPClient(t, func(req *http.Request) (*http.Response, error) {
		body, err := io.ReadAll(req.Body)
		if err != nil {
			t.Fatalf("read request body: %v", err)
		}
		bodies = append(bodies, string(body))
		if len(bodies) == 1 {
			return nil, errors.New("temporary network failure")
		}
		return cqieOKResponse(), nil
	})

	cache := &CqieUserCache{userId: "user"}
	_, err := cache.SubmitStudyTimeApi(
		"study", "version", "course", "student-course", "unit", "video",
		time.Date(2026, 7, 11, 12, 0, 0, 0, time.UTC), "courseware",
		1, 2, 3, 1, nil,
	)
	if err != nil {
		t.Fatalf("SubmitStudyTimeApi returned error: %v", err)
	}

	if len(bodies) != 2 {
		t.Fatalf("request count = %d, want 2", len(bodies))
	}
	for i, body := range bodies {
		if !strings.Contains(body, `"coursewareId": "courseware"`) {
			t.Fatalf("request %d body lost coursewareId: %s", i+1, body)
		}
	}
}

func TestSaveSegmentStudyTimeApiRetryPreservesSegmentEndpointAndFields(t *testing.T) {
	var paths []string
	var bodies []string
	installCqieCourseHTTPClient(t, func(req *http.Request) (*http.Response, error) {
		body, err := io.ReadAll(req.Body)
		if err != nil {
			t.Fatalf("read request body: %v", err)
		}
		paths = append(paths, req.URL.Path)
		bodies = append(bodies, string(body))
		if len(paths) == 1 {
			return nil, errors.New("temporary network failure")
		}
		return cqieOKResponse(), nil
	})

	cache := &CqieUserCache{studentId: "student", deptId: "dept", orgMajorId: "major"}
	_, err := cache.SaveSegmentStudyTimeApi(
		"course", "student-course", "unit", "video", "courseware",
		"segment", "9", "version", 1, 2, 1, nil,
	)
	if err != nil {
		t.Fatalf("SaveSegmentStudyTimeApi returned error: %v", err)
	}

	wantPath := "/gateway/system/orgStudent/segment/saveStudyVideoPlan"
	if len(paths) != 2 || paths[0] != wantPath || paths[1] != wantPath {
		t.Fatalf("retry paths = %#v, want [%q %q]", paths, wantPath, wantPath)
	}
	for i, body := range bodies {
		if !strings.Contains(body, `"segmentKnowledgeId": "segment"`) ||
			!strings.Contains(body, `"maxCurrentPos": 9`) {
			t.Fatalf("request %d body lost segment fields: %s", i+1, body)
		}
	}
}
