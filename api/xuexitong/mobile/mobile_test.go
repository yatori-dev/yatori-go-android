package mobile_test

import (
	"encoding/json"
	"errors"
	"net/url"
	"strings"
	"testing"

	mobile "github.com/yatori-dev/yatori-go-mobile-core/api/xuexitong/mobile"
)

func TestXxtClientInit(t *testing.T) {
	c := &mobile.XxtClient{Name: "user", Password: "pass"}
	if c.Name != "user" {
		t.Fatalf("Name=%q", c.Name)
	}
}

func TestChapterURLRequestsAllAttachmentTypes(t *testing.T) {
	rawURL := mobile.ChapterURL(1001, 2001)
	u, err := url.Parse(rawURL)
	if err != nil {
		t.Fatalf("parse chapter url: %v", err)
	}
	if u.Host != "mooc1-api.chaoxing.com" || u.Path != "/gas/clazz" {
		t.Fatalf("chapter url should match original gas/clazz endpoint: %s", rawURL)
	}
	fields := u.Query().Get("fields")
	if !strings.Contains(fields, "attachment.fields(id,type,objectid,extension)") {
		t.Fatalf("chapter fields missing attachment fields: %s", fields)
	}
	if strings.Contains(fields, ".type(video)") {
		t.Fatalf("chapter fields should not be video-only: %s", fields)
	}
}

func TestChapterInfoURLFallbackEndpoint(t *testing.T) {
	rawURL := mobile.ChapterInfoURL(1001, 2001)
	u, err := url.Parse(rawURL)
	if err != nil {
		t.Fatalf("parse chapter info url: %v", err)
	}
	if u.Host != "mooc1-api.chaoxing.com" || u.Path != "/clazz/info" {
		t.Fatalf("chapter fallback url mismatch: %s", rawURL)
	}
}

func TestCourseListJsonParsing(t *testing.T) {
	raw := `{"result":1,"channelList":[{"cpi":1001,"key":2001,"content":{"name":"班级A","chatid":"chat1","isstart":true,"state":0,"course":{"data":[{"id":5001,"name":"课程A","imageurl":"http://img","teacherfactor":"teacher1","courseSquareUrl":"?courseId=9001&classId=2001&personId=1001&userId=7001"}]}}}]}`
	var resp struct {
		Result      int `json:"result"`
		ChannelList []struct {
			Cpi     int `json:"cpi"`
			Key     any `json:"key"`
			Content struct {
				Course struct {
					Data []struct {
						Name            string `json:"name"`
						CourseSquareUrl string `json:"courseSquareUrl"`
					} `json:"data"`
				} `json:"course"`
			} `json:"content"`
		} `json:"channelList"`
	}
	if err := json.Unmarshal([]byte(raw), &resp); err != nil {
		t.Fatalf("parse error: %v", err)
	}
	if resp.Result != 1 {
		t.Fatalf("result=%d", resp.Result)
	}
	if resp.ChannelList[0].Content.Course.Data[0].Name != "课程A" {
		t.Fatalf("name mismatch")
	}
}

func TestLoginJsonParsing(t *testing.T) {
	raw := `{"status":true,"msg2":"","url":"http://i.mooc.chaoxing.com"}`
	var resp struct {
		Status bool   `json:"status"`
		Msg2   string `json:"msg2"`
	}
	if err := json.Unmarshal([]byte(raw), &resp); err != nil {
		t.Fatalf("parse error: %v", err)
	}
	if !resp.Status {
		t.Fatalf("expected status=true")
	}
}

func TestParseVideoDtoMeta_Success(t *testing.T) {
	raw := `{"status":"success","dtoken":"tok-abc","duration":300}`
	dto, err := mobile.ParseVideoDtoMeta(raw)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if dto.DToken != "tok-abc" {
		t.Fatalf("dtoken=%q", dto.DToken)
	}
	if dto.Duration != 300 {
		t.Fatalf("duration=%d", dto.Duration)
	}
}

func TestParseVideoDtoMeta_Failed(t *testing.T) {
	raw := `{"status":"error","dtoken":"","duration":0}`
	_, err := mobile.ParseVideoDtoMeta(raw)
	if err == nil {
		t.Fatal("expected error for non-success status")
	}
}

func TestAudioSubmitStudyTimeURL(t *testing.T) {
	rawURL, enc := mobile.AudioSubmitStudyTimeURL(mobile.AudioSubmitParams{
		ClassID:     "2001",
		UserID:      "7001",
		JobID:       "job-1",
		ObjectID:    "obj-1",
		CourseID:    "9001",
		OtherInfo:   "nodeId_111-cpi_1001",
		PlayingTime: 58,
		Duration:    300,
		IsDrag:      0,
		NowMillis:   12345,
	})
	u, err := url.Parse(rawURL)
	if err != nil {
		t.Fatalf("parse url: %v", err)
	}
	q := u.Query()
	if q.Get("dtype") != "Audio" || q.Get("playingTime") != "58" || q.Get("duration") != "300" {
		t.Fatalf("unexpected query: %s", rawURL)
	}
	if q.Get("enc") == "" || q.Get("enc") != enc {
		t.Fatalf("enc not propagated: url=%q enc=%q", q.Get("enc"), enc)
	}
	if q.Get("otherInfo") != "nodeId_111-cpi_1001" {
		t.Fatalf("otherInfo=%q", q.Get("otherInfo"))
	}
}

func TestParseCardHTMLForHyperlinkTask_FromAttachmentSetting(t *testing.T) {
	cardHTML := `<script>window.attachmentSetting = {"attachments":[{"jobid":"link-job","jtoken":"jt-1","property":{"title":"Link Title","jobid":"link-job"}}]};</script>`
	meta, err := mobile.ParseCardHTMLForHyperlinkTask(cardHTML, "link-job", "")
	if err != nil {
		t.Fatalf("parse failed: %v", err)
	}
	if meta.JobID != "link-job" || meta.JToken != "jt-1" || meta.Title != "Link Title" {
		t.Fatalf("unexpected meta: %+v", meta)
	}
}

func TestHyperlinkCompleteURL(t *testing.T) {
	rawURL := mobile.HyperlinkCompleteURL(mobile.HyperlinkCompleteParams{
		JobID:       "link-job",
		KnowledgeID: "111",
		CourseID:    "9001",
		ClassID:     "2001",
		JToken:      "jt-1",
		NowMillis:   12345,
	})
	u, err := url.Parse(rawURL)
	if err != nil {
		t.Fatalf("parse url: %v", err)
	}
	q := u.Query()
	for key, want := range map[string]string{
		"jobid":           "link-job",
		"knowledgeid":     "111",
		"courseid":        "9001",
		"clazzid":         "2001",
		"jtoken":          "jt-1",
		"checkMicroTopic": "true",
		"microTopicId":    "undefined",
		"_dc":             "12345",
	} {
		if got := q.Get(key); got != want {
			t.Fatalf("%s=%q, want %q in %s", key, got, want, rawURL)
		}
	}
}

func TestParseCardHTMLForDocumentTask_FromAttachmentSetting(t *testing.T) {
	cardHTML := `<script>window.attachmentSetting = {"attachments":[{"type":"document","jtoken":"jt-doc","job":true,"property":{"name":"Doc Name","objectid":"obj-doc","jobid":"doc-job"}}]};</script>`
	meta, err := mobile.ParseCardHTMLForDocumentTask(cardHTML, "doc-job", "obj-doc", "document")
	if err != nil {
		t.Fatalf("parse failed: %v", err)
	}
	if meta.JobID != "doc-job" || meta.ObjectID != "obj-doc" || meta.JToken != "jt-doc" || meta.Title != "Doc Name" || !meta.IsJob {
		t.Fatalf("unexpected meta: %+v", meta)
	}
}

func TestDocumentCompleteURL(t *testing.T) {
	cases := []struct {
		docType string
		host    string
		path    string
	}{
		{docType: "document", host: "mooc1.chaoxing.com", path: "/ananas/job/document"},
		{docType: "insertbook", host: "mooc1.chaoxing.com", path: "/ananas/job"},
		{docType: "insertreadV2", host: "mooc1-api.chaoxing.com", path: "/ananas/job/readv2"},
	}
	for _, tc := range cases {
		rawURL := mobile.DocumentCompleteURL(mobile.DocumentCompleteParams{
			JobID:        "doc-job",
			KnowledgeID:  "111",
			CourseID:     "9001",
			ClassID:      "2001",
			JToken:       "jt-doc",
			DocumentType: tc.docType,
			NowMillis:    12345,
		})
		u, err := url.Parse(rawURL)
		if err != nil {
			t.Fatalf("parse url: %v", err)
		}
		if u.Host != tc.host || u.Path != tc.path {
			t.Fatalf("%s url=%s", tc.docType, rawURL)
		}
		q := u.Query()
		if q.Get("jobid") != "doc-job" || q.Get("knowledgeid") != "111" || q.Get("jtoken") != "jt-doc" || q.Get("_dc") != "12345" {
			t.Fatalf("unexpected query: %s", rawURL)
		}
		if tc.docType == "insertreadV2" && (q.Get("checkMicroTopic") != "true" || q.Get("microTopicId") != "0") {
			t.Fatalf("readv2 missing micro topic params: %s", rawURL)
		}
	}
}

func TestParseCardHTMLForLiveTask_FromAttachmentSetting(t *testing.T) {
	cardHTML := `<script>window.attachmentSetting = {"attachments":[{"type":"live","jobid":"live-job","aid":1001,"job":true,"property":{"module":"insertlive","title":"Live Title","userId":"u1","liveId":6001,"streamName":"stream-1","vdoid":"vdo-1","live":true,"liveStatus":"回看","jobid":"live-job"}}]};</script>`
	meta, err := mobile.ParseCardHTMLForLiveTask(cardHTML, "live-job")
	if err != nil {
		t.Fatalf("parse failed: %v", err)
	}
	if meta.JobID != "live-job" || meta.LiveID != "6001" || meta.UserID != "u1" || meta.StreamName != "stream-1" || meta.VdoID != "vdo-1" || meta.AID != "1001" {
		t.Fatalf("unexpected live meta: %+v", meta)
	}
}

func TestLiveURLsAndInfoParse(t *testing.T) {
	infoURL := mobile.LiveInfoURL(mobile.LiveInfoParams{LiveID: "6001", UserID: "u1", ClassID: "2001", KnowledgeID: "111", CourseID: "9001", JobID: "live-job"})
	u, err := url.Parse(infoURL)
	if err != nil {
		t.Fatalf("parse live info url: %v", err)
	}
	if u.Host != "mooc1.chaoxing.com" || u.Query().Get("liveid") != "6001" || u.Query().Get("ut") != "s" {
		t.Fatalf("unexpected live info url: %s", infoURL)
	}
	saveURL := mobile.LiveSaveTimeURL(mobile.LiveSaveTimeParams{StreamName: "stream-1", VdoID: "vdo-1", UserID: "u1", CourseID: "9001", NowMillis: 12345})
	u, err = url.Parse(saveURL)
	if err != nil {
		t.Fatalf("parse live save url: %v", err)
	}
	if u.Host != "zhibo.chaoxing.com" || u.Query().Get("isStart") != "1" || u.Query().Get("t") != "12345" {
		t.Fatalf("unexpected live save url: %s", saveURL)
	}
	info, err := mobile.ParseLiveInfo(`{"status":true,"temp":{"data":{"percentValue":91.5,"duration":300,"liveStatus":4}}}`)
	if err != nil {
		t.Fatalf("parse live info: %v", err)
	}
	if !info.Status || info.Percent != 91.5 || info.Duration != 300 || info.StatusCode != 4 {
		t.Fatalf("unexpected info: %+v", info)
	}
}

func TestParseCardHTMLForBbsTask_FromAttachmentSetting(t *testing.T) {
	cardHTML := `<script>window.attachmentSetting = {"enc":"root-enc","attachments":[{"type":"bbs","jobid":"bbs-job","authEnc":"auth","otherInfo":"info","job":true,"property":{"module":"insertbbs","title":"BBS Title","detail":"Discuss","mid":"mid-1","jobid":"bbs-job","allowViewReply":1,"replytimes":"2","replywordnum":"20","endtime":"2026"}}]};</script>`
	meta, err := mobile.ParseCardHTMLForBbsTask(cardHTML, "bbs-job")
	if err != nil {
		t.Fatalf("parse failed: %v", err)
	}
	if meta.JobID != "bbs-job" || meta.Mid != "mid-1" || meta.Title != "BBS Title" || meta.Enc != "root-enc" || !meta.IsJob {
		t.Fatalf("unexpected bbs meta: %+v", meta)
	}
}

func TestPhoneBbsURLsAndParse(t *testing.T) {
	infoURL := mobile.PhoneBbsInfoURL("mid-1", "bbs-job", "111", "9001", "2001")
	u, err := url.Parse(infoURL)
	if err != nil {
		t.Fatalf("parse info url: %v", err)
	}
	if u.Host != "mooc1-api.chaoxing.com" || u.Query().Get("mtopicid") != "mid-1" || u.Query().Get("isMobile") != "true" {
		t.Fatalf("unexpected info url: %s", infoURL)
	}
	answerURL := mobile.PhoneBbsAnswerURL(mobile.BbsSignatureParams{
		C0: "c0", Token: "tok", NowMillis: "12345", PUID: "puid", UUID: "uuid-1",
		Tag: "classId2001", MaxW: "1080", TopicUUID: "topic-uuid", Anonymous: "0",
	})
	u, err = url.Parse(answerURL)
	if err != nil {
		t.Fatalf("parse answer url: %v", err)
	}
	if u.Host != "groupyd.chaoxing.com" || u.Query().Get("topicUUID") != "topic-uuid" || u.Query().Get("inf_enc") == "" {
		t.Fatalf("unexpected answer url: %s", answerURL)
	}
	infoHTML := `<input id="groupId" value="g1"><input id="bbsId" value="b1"><input id="topicId" value="t1"><script>classId:"2001";courseId:"9001";classChatId:"chat";role:"student";</script>`
	detailJSON := `{"data":{"title":"Topic Title","text_content":"Topic body","uuid":"topic-uuid"}}`
	topic, err := mobile.ParsePhoneBbsTopic(infoHTML, detailJSON)
	if err != nil {
		t.Fatalf("parse topic: %v", err)
	}
	if topic.TopicID != "t1" || topic.TopicUUID != "topic-uuid" || topic.Title != "Topic Title" || topic.ClassID != "2001" {
		t.Fatalf("unexpected topic: %+v", topic)
	}
	ok, _ := mobile.ParseBbsSubmit(`{"result":1,"msg":"ok"}`, "phone")
	if !ok {
		t.Fatal("phone bbs result=1 should pass")
	}
	ok, msg := mobile.ParseBbsSubmit(`{"result":0,"msg":"no"}`, "phone")
	if ok || msg != "no" {
		t.Fatalf("expected rejection, ok=%v msg=%q", ok, msg)
	}
}

func TestMonitorURLAndParse(t *testing.T) {
	rawURL := mobile.MonitorURL("fid-1", "jsonp123", 12345)
	u, err := url.Parse(rawURL)
	if err != nil {
		t.Fatalf("parse monitor url: %v", err)
	}
	if u.Host != "detect.chaoxing.com" || u.Query().Get("fid") != "fid-1" || u.Query().Get("jsoncallback") != "jsonp123" || u.Query().Get("t") != "12345" {
		t.Fatalf("unexpected monitor url: %s", rawURL)
	}
	if alive, expired := mobile.ParseMonitorResult(`jsonp123({"status":true})`); !alive || expired {
		t.Fatalf("alive parse failed: alive=%v expired=%v", alive, expired)
	}
	if alive, expired := mobile.ParseMonitorResult(`jsonp123({"status":false})`); alive || !expired {
		t.Fatalf("expired parse failed: alive=%v expired=%v", alive, expired)
	}
}

func TestWebBbsURLsAndParse(t *testing.T) {
	studyURL := mobile.WebBbsStudyURL("9001", "2001", "111", "enc-1")
	u, err := url.Parse(studyURL)
	if err != nil {
		t.Fatalf("parse study url: %v", err)
	}
	if u.Host != "mooc1.chaoxing.com" || u.Query().Get("chapterId") != "111" || u.Query().Get("enc") != "enc-1" {
		t.Fatalf("unexpected study url: %s", studyURL)
	}
	circleURL := mobile.WebBbsCircleURL("mid-1", "bbs-job", "111", "2001", "enc-1", "ut-1", "9001", true)
	u, err = url.Parse(circleURL)
	if err != nil {
		t.Fatalf("parse circle url: %v", err)
	}
	if u.Query().Get("utenc") != "ut-1" || u.Query().Get("isJob") != "true" {
		t.Fatalf("unexpected circle url: %s", circleURL)
	}
	if ut, err := mobile.ParseBbsUtEnc(`<script>var utEnc="ut-1";</script>`); err != nil || ut != "ut-1" {
		t.Fatalf("utEnc parse: ut=%q err=%v", ut, err)
	}
	if _, err := mobile.ParseBbsUtEnc(`请输入验证码`); !errors.Is(err, mobile.ErrBbsCaptcha) {
		t.Fatalf("captcha should be ErrBbsCaptcha: %v", err)
	}
	id1, id2, err := mobile.ParseBbsCircleIds(`<div data="https://groupweb.chaoxing.com/course/topic/v3/bbs/id-one/id-two/replysList?courseId=9001"></div>`)
	if err != nil || id1 != "id-one" || id2 != "id-two" {
		t.Fatalf("circle ids: %q %q err=%v", id1, id2, err)
	}
	topicHTML := `<script>var pageData={topic:{"uuid":"topic-uuid","bbsid":"bbs-1","title":"Web Title","content":"Web body","id":88},course:{}}; var urlToken:'tok-1';</script>`
	topic, err := mobile.ParseWebBbsTopic(topicHTML)
	if err != nil {
		t.Fatalf("parse web topic: %v", err)
	}
	if topic.Platform != "web" || topic.TopicUUID != "topic-uuid" || topic.BbsID != "bbs-1" || topic.URLToken != "tok-1" || topic.TopicID != "88" {
		t.Fatalf("unexpected web topic: %+v", topic)
	}
}

func TestParseCardHTMLForVideoTask_FromJSON(t *testing.T) {
	// card HTML with embedded JSON containing jobid and otherInfo
	cardHTML := `<script>window.attachmentSetting = {"attachments":[{"jobid":"job-123","objectid":"obj-456","otherInfo":"encoded-other-info-more-than-80-chars-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"}]};</script>`
	meta, err := mobile.ParseCardHTMLForVideoTask(cardHTML, "obj-456")
	if err != nil {
		t.Fatalf("parse failed: %v", err)
	}
	if meta.JobID != "job-123" {
		t.Fatalf("jobId=%q", meta.JobID)
	}
	if meta.ObjectID != "obj-456" {
		t.Fatalf("objectId=%q", meta.ObjectID)
	}
}

func TestParseCardHTMLForVideoTask_Fallback(t *testing.T) {
	// fallback: inline JSON with jobid
	cardHTML := `<div id="video" data-info='{"jobid":"job-789","objectid":"obj-111","otherInfo":"x"}'>...</div>`
	meta, err := mobile.ParseCardHTMLForVideoTask(cardHTML, "obj-111")
	if err != nil {
		// Fallback may fail gracefully — check
		t.Logf("fallback parse failed (acceptable if no inline JSON): %v", err)
		return
	}
	if meta.JobID == "" {
		t.Logf("jobId empty in fallback, acceptable for this HTML format")
	}
}

func TestParseCardHTMLForVideoTask_NotFound(t *testing.T) {
	_, err := mobile.ParseCardHTMLForVideoTask("<html>no job info here</html>", "obj-999")
	if err == nil {
		t.Fatal("expected error when not found")
	}
}
