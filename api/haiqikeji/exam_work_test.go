package haiqikeji

import "testing"

func TestParseQuizList_Work(t *testing.T) {
	raw := `{"code":200,"msg":"ok","data":{"workInfo":[{"workId":1011,"title":"作业一"},{"workId":1012,"title":"作业二"}]}}`
	got, err := ParseQuizList(raw, "work")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(got) != 2 || got[0].ID != "1011" || got[0].Title != "作业一" {
		t.Fatalf("unexpected: %+v", got)
	}
}

func TestParseQuizList_Exam(t *testing.T) {
	raw := `{"code":200,"data":{"examInfo":[{"examId":2022,"title":"期末考试"}]}}`
	got, err := ParseQuizList(raw, "exam")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(got) != 1 || got[0].ID != "2022" {
		t.Fatalf("unexpected: %+v", got)
	}
}

func TestParseQuizList_RejectsServerError(t *testing.T) {
	if _, err := ParseQuizList(`{"code":500,"msg":"系统异常"}`, "exam"); err == nil {
		t.Fatal("non-200 quiz list must error")
	}
}

func TestParseDetailRecords(t *testing.T) {
	raw := `{"code":200,"data":{"workDetail":[{"id":501,"paperId":6196,"classId":1016378},{"id":502,"paperId":0,"classId":0}]}}`
	got, err := ParseDetailRecords(raw)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(got) != 2 {
		t.Fatalf("want 2 records, got %d", len(got))
	}
	if got[0].ID != "501" || got[0].PaperID != "6196" || got[0].ClassID != "1016378" {
		t.Fatalf("rec0=%+v", got[0])
	}
	// paperId=0 / classId=0 should be left empty (not "0")
	if got[1].PaperID != "" || got[1].ClassID != "" {
		t.Fatalf("rec1 should have empty paperId/classId: %+v", got[1])
	}
}

func TestParseDetailRecords_RejectsServerError(t *testing.T) {
	if _, err := ParseDetailRecords(`{"code":401,"msg":"令牌不匹配"}`); err == nil {
		t.Fatal("non-200 detail response must error")
	}
}

func TestParseStartQuestions_WorkTopics(t *testing.T) {
	raw := `{"code":200,"data":{"workTopics":[{"id":7001,"recordId":88,"type":1,"topic":"<p>中国的首都是？</p>","option":[{"idx":"A","answer":"北京","scale":100},{"idx":"B","answer":"上海"},{"idx":"C","answer":"广州"}]}]}}`
	got, err := ParseStartQuestions(raw)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(got) != 1 {
		t.Fatalf("want 1 topic, got %d", len(got))
	}
	tp := got[0]
	if tp.TopicID != "7001" || tp.RecordID != "88" || tp.Type != HqkjTypeSingle {
		t.Fatalf("topic ids/type: %+v", tp)
	}
	if tp.Content != "中国的首都是？" {
		t.Fatalf("content=%q (HTML not stripped?)", tp.Content)
	}
	if len(tp.Options) != 3 || tp.Options[0] != "北京" {
		t.Fatalf("options=%v", tp.Options)
	}
	if len(tp.OptionIdx) != 3 || tp.OptionIdx[0] != "A" || tp.OptionIdx[2] != "C" {
		t.Fatalf("optionIdx=%v", tp.OptionIdx)
	}
}

// consult fallback path: questions arrive under data.workResult with topicId/wrId/waId.
func TestParseStartQuestions_ConsultResult(t *testing.T) {
	raw := `{"code":200,"data":{"workResult":[{"topicId":9001,"wrId":42,"waId":7,"type":2,"topic":"多选题干","option":[{"idx":"A","answer":"甲"},{"idx":"B","answer":"乙"}]}]}}`
	got, err := ParseStartQuestions(raw)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(got) != 1 || got[0].TopicID != "9001" || got[0].WrID != "42" || got[0].WaID != "7" {
		t.Fatalf("unexpected consult topic: %+v", got)
	}
}

func TestParseStartQuestions_PreservesFillWithoutOptions(t *testing.T) {
	raw := `{"code":200,"data":{"workTopics":[{"id":1,"type":4,"topic":"请填写首都","option":[]}]}}`
	got, err := ParseStartQuestions(raw)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(got) != 1 || got[0].TopicID != "1" || got[0].Type != HqkjTypeFill || got[0].Content != "请填写首都" {
		t.Fatalf("fill question must be preserved: %+v", got)
	}
}

func TestParseStartQuestions_PreservesShortWithoutOptions(t *testing.T) {
	raw := `{"code":200,"data":{"examTopics":[{"id":2,"type":5,"topic":"请简述原因"}]}}`
	got, err := ParseStartQuestions(raw)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(got) != 1 || got[0].TopicID != "2" || got[0].Type != HqkjTypeShort || got[0].Content != "请简述原因" {
		t.Fatalf("short question must be preserved: %+v", got)
	}
}

func TestParseStartQuestions_RejectsServerError(t *testing.T) {
	if _, err := ParseStartQuestions(`{"code":500,"msg":"系统异常"}`); err == nil {
		t.Fatal("non-200 start response must error")
	}
	if _, err := ParseStartQuestions(`not-json`); err == nil {
		t.Fatal("malformed JSON must error")
	}
}

func TestParseAnswerResult(t *testing.T) {
	if msg, err := ParseAnswerResult(`{"code":200,"msg":"提交成功"}`); err != nil || msg != "提交成功" {
		t.Fatalf("ok submit: msg=%q err=%v", msg, err)
	}
	if _, err := ParseAnswerResult(`{"code":500,"msg":"系统异常"}`); err == nil {
		t.Fatal("non-200 must error")
	}
}

func TestFormatHqkjAnswer_Single(t *testing.T) {
	// host text "上海" maps to the matching option's server idx "B"
	got := FormatHqkjAnswer(HqkjTypeSingle, []string{"北京", "上海", "广州"}, []string{"A", "B", "C"}, []string{"上海"})
	if len(got) != 1 || got[0] != "B" {
		t.Fatalf("single: got %v, want [B]", got)
	}
}

func TestFormatHqkjAnswer_PreservesServerOptionIdx(t *testing.T) {
	got := FormatHqkjAnswer(HqkjTypeSingle, []string{"北京", "上海", "广州"}, []string{"A", "B", "C"}, []string{"B"})
	if len(got) != 1 || got[0] != "B" {
		t.Fatalf("server option idx must pass through unchanged: got %v, want [B]", got)
	}
}

func TestFormatHqkjAnswer_Multi(t *testing.T) {
	got := FormatHqkjAnswer(HqkjTypeMulti, []string{"甲", "乙", "丙"}, []string{"A", "B", "C"}, []string{"甲", "丙"})
	if len(got) != 2 || got[0] != "A" || got[1] != "C" {
		t.Fatalf("multi: got %v, want [A C]", got)
	}
}

func TestFormatHqkjAnswer_FillPassthrough(t *testing.T) {
	got := FormatHqkjAnswer(HqkjTypeFill, nil, nil, []string{"答案1", "答案2"})
	if len(got) != 2 || got[0] != "答案1" {
		t.Fatalf("fill: got %v", got)
	}
}

func TestFormatHqkjAnswer_FallbackToFirstIdx(t *testing.T) {
	// empty host answers on a choice question fall back to the first option idx
	got := FormatHqkjAnswer(HqkjTypeSingle, []string{"x", "y"}, []string{"A", "B"}, nil)
	if len(got) != 1 || got[0] != "A" {
		t.Fatalf("fallback: got %v, want [A]", got)
	}
}

// the idx letters come from the server, not positional A/B/C — verify a non-standard
// idx set (e.g. server gives B/D/F) is honoured.
func TestFormatHqkjAnswer_ServerProvidedIdx(t *testing.T) {
	got := FormatHqkjAnswer(HqkjTypeSingle, []string{"opt-x", "opt-y", "opt-z"}, []string{"B", "D", "F"}, []string{"opt-z"})
	if len(got) != 1 || got[0] != "F" {
		t.Fatalf("server idx: got %v, want [F]", got)
	}
}
