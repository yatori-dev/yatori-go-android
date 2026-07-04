package mobilecore

import (
	"encoding/json"
	"testing"

	ttcdwAgg "github.com/yatori-dev/yatori-go-mobile-core/aggregation/ttcdw"
	ttcdwApi "github.com/yatori-dev/yatori-go-mobile-core/api/ttcdw"
)

func fakeClassRoomProvider(rooms []ttcdwAgg.TtcdwClassRoom) func() {
	orig := ttcdwClassRoomProvider
	ttcdwClassRoomProvider = func(_ *ttcdwApi.TtcdwUserCache, _ ttcdwAgg.TtcdwProject) ([]ttcdwAgg.TtcdwClassRoom, error) {
		return rooms, nil
	}
	return func() { ttcdwClassRoomProvider = orig }
}

func TestGetCourseDetailTtcdw_Success(t *testing.T) {
	resetState()
	Init("/tmp/test")

	rooms := []ttcdwAgg.TtcdwClassRoom{
		{Name: "组", Title: "必修", ItemId: "item-1", SegmentId: "seg-1"},
		{Name: "组2", Title: "选修", ItemId: "item-2", SegmentId: "seg-2"},
	}
	restore := fakeClassRoomProvider(rooms)
	defer restore()

	sessJSON := `{"platform":"ttcdw","account":"u","cookies":"SESS=x"}`
	courseJSON := `{"platform":"ttcdw","id":"proj-1","raw":{"classId":"cls-1","orgId":"org-1"}}`
	e := parseEnvelope(t, GetCourseDetail(sessJSON, courseJSON))
	if !e.OK {
		t.Fatalf("GetCourseDetail failed: %s", e.Error)
	}
	raw, _ := json.Marshal(e.Data)
	var detail CourseDetailResult
	json.Unmarshal(raw, &detail)

	if detail.Platform != "ttcdw" {
		t.Fatalf("platform=%q", detail.Platform)
	}
	if detail.ParentID != "proj-1" {
		t.Fatalf("parentId=%q", detail.ParentID)
	}
	if len(detail.Items) != 2 {
		t.Fatalf("expected 2 items, got %d", len(detail.Items))
	}
	if detail.Items[0].ID != "item-1" {
		t.Fatalf("item[0].id=%q", detail.Items[0].ID)
	}
}

func TestGetCourseDetailTtcdw_PlatformFromSession(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeClassRoomProvider(nil)
	defer restore()

	// courseJSON without platform — should fall back to session platform
	sessJSON := `{"platform":"ttcdw","account":"u","cookies":"SESS=x"}`
	courseJSON := `{"id":"proj-1","raw":{"classId":"cls-1"}}`
	e := parseEnvelope(t, GetCourseDetail(sessJSON, courseJSON))
	if !e.OK {
		t.Fatalf("GetCourseDetail should succeed: %s", e.Error)
	}
}

func TestGetCourseDetailNotInitialized(t *testing.T) {
	resetState()
	e := parseEnvelope(t, GetCourseDetail(`{"platform":"ttcdw"}`, `{"id":"x"}`))
	if e.OK {
		t.Fatal("should fail before Init")
	}
}

func TestGetCourseDetailInvalidJSON(t *testing.T) {
	resetState()
	Init("/tmp/test")
	e := parseEnvelope(t, GetCourseDetail("not-json", `{}`))
	if e.OK {
		t.Fatal("invalid sessionJSON should fail")
	}
	e2 := parseEnvelope(t, GetCourseDetail(`{"platform":"ttcdw"}`, "not-json"))
	if e2.OK {
		t.Fatal("invalid courseJSON should fail")
	}
}

func TestGetCourseDetailUnsupportedPlatform(t *testing.T) {
	resetState()
	Init("/tmp/test")
	e := parseEnvelope(t, GetCourseDetail(
		`{"platform":"mooc","token":"x"}`,
		`{"platform":"mooc","id":"x"}`,
	))
	if e.OK {
		t.Fatal("unsupported platform should fail")
	}
}

func TestCourseInputRoundtrip(t *testing.T) {
	ci := CourseInput{
		Platform: "ttcdw",
		ID:       "proj-99",
		Raw:      map[string]interface{}{"classId": "c1", "orgId": "o1"},
	}
	b, _ := json.Marshal(ci)
	var out CourseInput
	json.Unmarshal(b, &out)
	if out.ID != "proj-99" || out.Platform != "ttcdw" {
		t.Fatalf("roundtrip mismatch: %+v", out)
	}
}
