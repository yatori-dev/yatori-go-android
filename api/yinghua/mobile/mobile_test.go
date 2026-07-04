package mobile_test

import (
	"testing"

	"github.com/yatori-dev/yatori-go-mobile-core/api/yinghua/mobile"
)

func TestParseLoginResponse_Success(t *testing.T) {
	raw := `{"status":true,"redirect":"http://example.com/index?token=tok-abc&sign=sig-xyz"}`
	token, sign, err := mobile.ParseLoginResponse(raw)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if token != "tok-abc" {
		t.Fatalf("token=%q", token)
	}
	if sign != "sig-xyz" {
		t.Fatalf("sign=%q", sign)
	}
}

func TestParseLoginResponse_Failed(t *testing.T) {
	raw := `{"status":false,"msg":"账号密码不正确","refresh_code":1}`
	_, _, err := mobile.ParseLoginResponse(raw)
	if err == nil {
		t.Fatal("expected error for failed login")
	}
}

func TestParseLoginResponse_MissingToken(t *testing.T) {
	raw := `{"status":true,"redirect":"http://example.com/index"}`
	_, _, err := mobile.ParseLoginResponse(raw)
	if err == nil {
		t.Fatal("expected error when token absent from redirect")
	}
}

func TestParseCourseListResp_Success(t *testing.T) {
	raw := `{"_code":0,"status":true,"msg":"获取数据成功","result":{"list":[{"id":1001,"name":"课程A","mode":0,"progress":50.0,"startDate":"2024-01-01","endDate":"2024-12-31","videoCount":10,"videoLearned":5}]}}`
	resp, err := mobile.ParseCourseListResp(raw)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(resp.Result.List) != 1 || resp.Result.List[0].Name != "课程A" {
		t.Fatalf("unexpected: %+v", resp.Result.List)
	}
}

// status=true + _code=0 with non-standard msg must still succeed.
func TestParseCourseListResp_NonStandardMsg(t *testing.T) {
	raw := `{"_code":0,"status":true,"msg":"success","result":{"list":[{"id":2001,"name":"课程B"}]}}`
	resp, err := mobile.ParseCourseListResp(raw)
	if err != nil {
		t.Fatalf("non-standard msg but status ok: %v", err)
	}
	if len(resp.Result.List) != 1 {
		t.Fatalf("expected 1 course, got %d", len(resp.Result.List))
	}
}

func TestParseCourseListResp_Timeout(t *testing.T) {
	raw := `{"_code":1,"status":false,"msg":"账号登录超时，请重新登录","result":{}}`
	_, err := mobile.ParseCourseListResp(raw)
	if err == nil {
		t.Fatal("expected error for timeout response")
	}
}

func TestParseCourseListResp_StatusFalseCodeZero(t *testing.T) {
	// status=false should fail even if _code happens to be 0
	raw := `{"_code":0,"status":false,"msg":"未知错误","result":{}}`
	_, err := mobile.ParseCourseListResp(raw)
	if err == nil {
		t.Fatal("status=false must fail")
	}
}

func TestParseCourseChapter_Success(t *testing.T) {
	raw := `{"_code":0,"status":true,"msg":"获取数据成功","result":{"list":[{"id":100,"name":"第1章","nodeList":[{"id":200,"name":"第1节","videoDuration":"1235","nodeLock":0,"tabVideo":true,"tabExam":false,"tabWork":false}]}]}}`
	chapters, err := mobile.ParseCourseChapter(raw)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(chapters) != 1 || len(chapters[0].NodeList) != 1 {
		t.Fatalf("unexpected chapters: %+v", chapters)
	}
	if chapters[0].NodeList[0].Name != "第1节" {
		t.Fatalf("node name=%q", chapters[0].NodeList[0].Name)
	}
}

// status=true + _code=0 with non-standard msg must still succeed.
func TestParseCourseChapter_NonStandardMsg(t *testing.T) {
	raw := `{"_code":0,"status":true,"msg":"ok","result":{"list":[{"id":101,"name":"ch1","nodeList":[]}]}}`
	chapters, err := mobile.ParseCourseChapter(raw)
	if err != nil {
		t.Fatalf("non-standard msg but status ok: %v", err)
	}
	if len(chapters) != 1 {
		t.Fatalf("expected 1 chapter")
	}
}

func TestParseCourseChapter_Failed(t *testing.T) {
	raw := `{"_code":1,"status":false,"msg":"获取数据失败","result":{}}`
	_, err := mobile.ParseCourseChapter(raw)
	if err == nil {
		t.Fatal("expected error")
	}
}

func TestParseNodeVideoMeta_Success(t *testing.T) {
	raw := `{"_code":0,"status":true,"msg":"获取数据成功","result":{"studyId":"study-001","videoDuration":1235}}`
	meta, err := mobile.ParseNodeVideoMeta(raw)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if meta.StudyID != "study-001" {
		t.Fatalf("studyId=%q", meta.StudyID)
	}
	if meta.VideoDuration != 1235 {
		t.Fatalf("videoDuration=%d", meta.VideoDuration)
	}
}

func TestParseNodeVideoMeta_Locked(t *testing.T) {
	raw := `{"_code":9,"status":false,"msg":"节点已被锁定","result":{}}`
	_, err := mobile.ParseNodeVideoMeta(raw)
	if err == nil {
		t.Fatal("expected error for locked node")
	}
}

func TestParseStudySubmitResult_Success(t *testing.T) {
	raw := `{"_code":0,"status":true,"msg":"提交成功","result":{"data":{"studyId":12345,"videoDuration":600}}}`
	meta, err := mobile.ParseStudySubmitResult(raw)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if meta.StudyID != "12345" || meta.VideoDuration != 600 {
		t.Fatalf("unexpected meta: %+v", meta)
	}
}

func TestParseStudySubmitResult_Failed(t *testing.T) {
	raw := `{"_code":1,"status":false,"msg":"提交失败"}`
	if _, err := mobile.ParseStudySubmitResult(raw); err == nil {
		t.Fatal("expected error for failed submit")
	}
}

func TestParseVideoRecordPage(t *testing.T) {
	raw := `{"_code":0,"status":true,"msg":"获取数据成功","result":{"list":[{"id":200,"name":"第1节","courseId":1001,"videoDuration":1200,"progress":100,"state":2,"viewedDuration":1200,"error":0}],"pageInfo":{"page":1,"pageCount":3}}}`
	page, err := mobile.ParseVideoRecordPage(raw)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if page.PageCount != 3 || len(page.Items) != 1 {
		t.Fatalf("unexpected page: %+v", page)
	}
	if page.Items[0].ID != "200" || page.Items[0].CourseID != "1001" || page.Items[0].Progress != 100 {
		t.Fatalf("unexpected item: %+v", page.Items[0])
	}
}

func TestParsePCVideoRecordPage(t *testing.T) {
	raw := `{"list":[{"id":"200","error":1,"errorMessage":"检测到可能使用并行播放刷课"}],"pageInfo":{"page":1,"pageCount":1}}`
	page, err := mobile.ParsePCVideoRecordPage(raw)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(page.Items) != 1 || page.Items[0].ID != "200" || page.Items[0].Error != 1 {
		t.Fatalf("unexpected page: %+v", page)
	}
}

func TestParseKeepAliveResult(t *testing.T) {
	if alive, expired := mobile.ParseKeepAliveResult(`{"status":true,"_code":0}`); !alive || expired {
		t.Fatalf("alive session: got alive=%v expired=%v, want true/false", alive, expired)
	}
	if alive, expired := mobile.ParseKeepAliveResult(`{"status":false,"_code":500}`); alive || !expired {
		t.Fatalf("expired session: got alive=%v expired=%v, want false/true", alive, expired)
	}
	// transient/gateway garbage → neither (host may retry)
	if alive, expired := mobile.ParseKeepAliveResult(`<html>502 Bad Gateway</html>`); alive || expired {
		t.Fatalf("gateway error: got alive=%v expired=%v, want false/false", alive, expired)
	}
}

// --- exam/work parsers ---

func TestParseWorkList(t *testing.T) {
	raw := `{"_code":0,"status":true,"msg":"获取数据成功","result":{"list":[{"id":11,"title":"作业1","score":100,"courseId":1001,"nodeId":200,"url":"/work?workId=W7&token=abc","allow":"1","frequency":"3"}]}}`
	items, err := mobile.ParseWorkList(raw)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(items) != 1 {
		t.Fatalf("want 1 work, got %d", len(items))
	}
	w := items[0]
	if w.WorkID != "W7" || w.CourseID != "1001" || w.NodeID != "200" || w.Allow != 1 || w.Frequency != 3 {
		t.Fatalf("unexpected work: %+v", w)
	}
}

func TestParseWorkList_Failed(t *testing.T) {
	if _, err := mobile.ParseWorkList(`{"_code":1,"status":false,"msg":"获取数据失败"}`); err == nil {
		t.Fatal("status=false must fail")
	}
}

func TestParseExamList(t *testing.T) {
	raw := `{"_code":0,"status":true,"msg":"获取数据成功","result":{"list":[{"id":22,"title":"考试1","score":100,"limitedTime":60,"courseId":1001,"nodeId":201,"url":"/exam?examId=E9&token=abc"}]}}`
	items, err := mobile.ParseExamList(raw)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(items) != 1 || items[0].ExamID != "E9" || items[0].LimitedTime != 60 {
		t.Fatalf("unexpected exam: %+v", items)
	}
}

func TestParseStartResult(t *testing.T) {
	if err := mobile.ParseStartResult(`{"_code":0,"status":true,"msg":"ok","result":{}}`); err != nil {
		t.Fatalf("code 0 should pass: %v", err)
	}
	if err := mobile.ParseStartResult(`{"_code":9,"status":false,"msg":"考试测试时间还未开始","result":{}}`); err == nil {
		t.Fatal("code 9 must fail")
	}
}

func TestParseAnswerSubmit(t *testing.T) {
	if msg, err := mobile.ParseAnswerSubmit(`{"_code":0,"status":true,"msg":"答题保存成功"}`); err != nil || msg != "答题保存成功" {
		t.Fatalf("ok submit: msg=%q err=%v", msg, err)
	}
	if _, err := mobile.ParseAnswerSubmit(`{"_code":1,"status":false,"msg":"提交失败"}`); err == nil {
		t.Fatal("status=false must fail")
	}
}

func TestFormatWireAnswer(t *testing.T) {
	// single: host text maps to the matching option's letter
	got := mobile.FormatWireAnswer(mobile.QTypeSingle, []string{"北京", "上海", "广州"}, []string{"上海"})
	if len(got) != 1 || got[0] != "B" {
		t.Fatalf("single: got %v, want [B]", got)
	}
	// multi: each text maps to a distinct letter
	got = mobile.FormatWireAnswer(mobile.QTypeMulti, []string{"甲选项", "乙选项", "丙选项"}, []string{"甲选项", "丙选项"})
	if len(got) != 2 || got[0] != "A" || got[1] != "C" {
		t.Fatalf("multi: got %v, want [A C]", got)
	}
	// fill: passthrough
	got = mobile.FormatWireAnswer(mobile.QTypeFill, nil, []string{"答案1", "答案2"})
	if len(got) != 2 || got[0] != "答案1" {
		t.Fatalf("fill: got %v", got)
	}
	// empty choice falls back to A
	got = mobile.FormatWireAnswer(mobile.QTypeSingle, []string{"x", "y"}, nil)
	if len(got) != 1 || got[0] != "A" {
		t.Fatalf("fallback: got %v, want [A]", got)
	}
}

func TestTurnExamTopic(t *testing.T) {
	html := `<ul class="nav">` +
		`<li><a data-id="ans-100" href="#" class="nav" id="t1" data-index="0" onclick="go()">1</a></li>` +
		`</ul>` +
		`<form method="post" action="/api/work/submit">` +
		`<span class="num">1.</span>` +
		`<span class="tag">单选</span>` +
		`<span class="txt">本题5分</span>` +
		`<div class="content" style="x"><p>新中国成立时间</p></div>` +
		`<ul>` +
		`<li><label><input type="radio" value="1" checked="checked" class="opt" name="q1"><span class="num">A</span><span class="txt">1949年10月1日</span></label></li>` +
		`<li><label><input type="radio" value="2" checked="checked" class="opt" name="q1"><span class="num">B</span><span class="txt">1950年10月1日</span></label></li>` +
		`</ul>` +
		`</form>`
	topics := mobile.TurnExamTopic(html)
	if len(topics) != 1 {
		t.Fatalf("want 1 topic, got %d: %+v", len(topics), topics)
	}
	tp := topics[0]
	if tp.Type != mobile.QTypeSingle {
		t.Fatalf("type=%q want %q", tp.Type, mobile.QTypeSingle)
	}
	if tp.AnswerID != "ans-100" {
		t.Fatalf("answerId=%q want ans-100", tp.AnswerID)
	}
	if len(tp.Options) != 2 || tp.Options[0] != "1949年10月1日" {
		t.Fatalf("options=%v", tp.Options)
	}
}
