package mobile

import "testing"

func TestParseKnowledgePoints_MultipleTypes(t *testing.T) {
	raw := `{"data":[{"card":{"data":[` +
		`{"cardorder":0,"description":"<iframe module=\"insertvideo\" data=\"{&quot;objectid&quot;:&quot;v-obj&quot;,&quot;jobid&quot;:1558340423438519,&quot;name&quot;:&quot;1.2.mp4&quot;}\"></iframe>"},` +
		`{"cardorder":1,"description":"<iframe module=\"insertdoc\" data=\"{&quot;objectid&quot;:&quot;d-obj&quot;,&quot;_jobid&quot;:&quot;doc-1&quot;}\"></iframe><iframe module=\"insertbbs\" data=\"{&quot;_jobid&quot;:&quot;bbs-1&quot;,&quot;title&quot;:&quot;Topic&quot;}\"></iframe>"},` +
		`{"cardorder":2,"description":"<iframe module=\"insertvideo\" data=\"\"></iframe>"}` +
		`]}}]}`
	points, err := ParseKnowledgePoints(raw)
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}
	if len(points) != 3 {
		t.Fatalf("expected 3 points, got %d: %+v", len(points), points)
	}
	// video: numeric jobid coerced to string, name preserved, cardIndex tracked.
	if points[0].Module != "insertvideo" || points[0].ObjectID != "v-obj" || points[0].JobID != "1558340423438519" {
		t.Fatalf("unexpected video point: %+v", points[0])
	}
	if points[0].Title != "1.2.mp4" || points[0].CardIndex != 0 {
		t.Fatalf("unexpected video title/cardIndex: %+v", points[0])
	}
	if points[1].Module != "insertdoc" || points[1].ObjectID != "d-obj" || points[1].JobID != "doc-1" || points[1].CardIndex != 1 {
		t.Fatalf("unexpected doc point: %+v", points[1])
	}
	if points[2].Module != "insertbbs" || points[2].JobID != "bbs-1" || points[2].CardIndex != 1 {
		t.Fatalf("unexpected bbs point: %+v", points[2])
	}
}

func TestParseKnowledgePoints_SkipsIframeWithoutIDs(t *testing.T) {
	// iframe has data but no objectid/jobid/workid → skipped.
	raw := `{"data":[{"card":{"data":[{"cardorder":0,"description":"<iframe module=\"insertvideo\" data=\"{&quot;foo&quot;:&quot;bar&quot;}\"></iframe>"}]}}]}`
	points, err := ParseKnowledgePoints(raw)
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}
	if len(points) != 0 {
		t.Fatalf("expected 0 points, got %+v", points)
	}
}

func TestParseKnowledgePoints_EmptyCardsNoError(t *testing.T) {
	points, err := ParseKnowledgePoints(`{"data":[{"card":{"data":[]}}]}`)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(points) != 0 {
		t.Fatalf("expected 0 points, got %+v", points)
	}
}

func TestParseKnowledgePoints_NonJSONError(t *testing.T) {
	if _, err := ParseKnowledgePoints("<html>login</html>"); err == nil {
		t.Fatal("expected error for non-JSON input")
	}
	if _, err := ParseKnowledgePoints(""); err == nil {
		t.Fatal("expected error for empty input")
	}
}

func TestParseNodePointStatus(t *testing.T) {
	// 111: fully complete; 222: in-progress; 333: totalcount=0 but unfinishcount>0 → total=unfinish.
	raw := `{"111":{"totalcount":3,"finishcount":3,"unfinishcount":0},"222":{"totalcount":4,"finishcount":1,"unfinishcount":3},"333":{"totalcount":0,"finishcount":0,"unfinishcount":2}}`
	m, err := ParseNodePointStatus(raw)
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}
	if len(m) != 3 {
		t.Fatalf("expected 3 nodes, got %d", len(m))
	}
	if s := m[111]; s.Total != 3 || s.Finished != 3 || !s.IsFinished() {
		t.Fatalf("node 111: %+v (IsFinished=%v)", s, s.IsFinished())
	}
	if s := m[222]; s.Total != 4 || s.Finished != 1 || s.IsFinished() {
		t.Fatalf("node 222: %+v (IsFinished=%v)", s, s.IsFinished())
	}
	// totalcount=0 + unfinishcount=2 → Total=2, so not finished.
	if s := m[333]; s.Total != 2 || s.Finished != 0 || s.IsFinished() {
		t.Fatalf("node 333: %+v (IsFinished=%v)", s, s.IsFinished())
	}
}

func TestParseNodePointStatus_NonJSONError(t *testing.T) {
	if _, err := ParseNodePointStatus("<html>"); err == nil {
		t.Fatal("expected error for non-JSON input")
	}
	if _, err := ParseNodePointStatus(""); err == nil {
		t.Fatal("expected error for empty input")
	}
}
