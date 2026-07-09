package mobilecore

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"regexp"
	"strconv"
	"strings"

	xxtmobile "github.com/yatori-dev/yatori-go-mobile-core/api/xuexitong/mobile"
)

func xxtClient(sess SessionData) *xxtmobile.XxtClient {
	c := &xxtmobile.XxtClient{
		Name:    sess.Account,
		UserID:  strOf(sess.Extra["userId"]),
		Cookies: cookieStrToSlice(sess.Cookies),
	}
	// The paper-fetch / media endpoints require userid=<puid>; when the session didn't carry
	// it, recover it from the UID cookie (what chaoxing's own pages use). Without it the work
	// fetch bounces to a "信息提示" page and media submits fail with code=13.
	if c.UserID == "" {
		c.UserID = c.Puid()
	}
	return c
}

// --- replaceable providers ---

var xxtLoginProvider = func(c *xxtmobile.XxtClient) (string, error) {
	return c.LoginApi(3)
}

var xxtCourseListProvider = func(c *xxtmobile.XxtClient) (string, error) {
	return c.CourseListApi(3, nil)
}

var xxtCardProvider = func(c *xxtmobile.XxtClient, classId, courseId, knowledgeId, cpi string) (string, error) {
	// Use the mobile SSR endpoint (mooc1-api /knowledge/cards?isPhone=1&control=true), which
	// embeds window.AttachmentSetting — the structure the parsers expect and the original
	// console/core relies on. The mooc2 web endpoint does NOT include AttachmentSetting.
	ci, _ := strconv.Atoi(classId)
	co, _ := strconv.Atoi(courseId)
	kn, _ := strconv.Atoi(knowledgeId)
	cp, _ := strconv.Atoi(cpi)
	return c.PageMobileChapterCardApi(ci, co, kn, 0, cp, 3, nil)
}

// xxtKnowledgeCardsProvider fetches a knowledge node's task-point cards (gas/knowledge).
// This is the authoritative task-point discovery source (mirrors console FetchChapterCords);
// the chapter tree's attachment field is only a legacy fallback.
var xxtKnowledgeCardsProvider = func(c *xxtmobile.XxtClient, nodeID, courseID int) (string, error) {
	return c.FetchKnowledgeCardsApi(nodeID, courseID, 3, nil)
}

// xxtPointStatusProvider fetches per-node task-point completion (job/myjobsnodesmap) so
// getTasks can skip already-finished nodes without fetching their cards.
var xxtPointStatusProvider = func(c *xxtmobile.XxtClient, nodes []int, clazzID, userID, cpi, courseID int) (string, error) {
	return c.FetchChapterPointStatusApi(nodes, clazzID, userID, cpi, courseID, 3, nil)
}

var xxtVideoDtoProvider = func(c *xxtmobile.XxtClient, objectID string, fid int) (string, error) {
	return c.FetchVideoDtoApi(objectID, fid, 3, nil)
}

var xxtSubmitStudyProvider = func(c *xxtmobile.XxtClient, p xxtmobile.VideoSubmitParams) (string, error) {
	return c.VideoSubmitStudyTimeWithMetaApi(p, 3, nil)
}

var xxtAudioSubmitProvider = func(c *xxtmobile.XxtClient,
	classID, userID, jobID, objectID, courseID, otherInfo string,
	playingTime, duration, isdrag int,
) (string, error) {
	return c.AudioSubmitStudyTimeApi(classID, userID, jobID, objectID, courseID, otherInfo, playingTime, duration, isdrag, 3, nil)
}

var xxtHyperlinkCompleteProvider = func(c *xxtmobile.XxtClient, jobID, knowledgeID, courseID, classID, jtoken string) (string, error) {
	return c.HyperlinkCompleteApi(jobID, knowledgeID, courseID, classID, jtoken, 3, nil)
}

var xxtDocumentCompleteProvider = func(c *xxtmobile.XxtClient, jobID, knowledgeID, courseID, classID, jtoken, documentType string) (string, error) {
	return c.DocumentCompleteApi(jobID, knowledgeID, courseID, classID, jtoken, documentType, 3, nil)
}

var xxtLiveInfoProvider = func(c *xxtmobile.XxtClient, liveID, userID, classID, knowledgeID, courseID, jobID string) (string, error) {
	return c.PullLiveInfoApi(liveID, userID, classID, knowledgeID, courseID, jobID, 3, nil)
}

var xxtLiveRelationProvider = func(c *xxtmobile.XxtClient, courseID, knowledgeID, jobID, aid string) (string, error) {
	return c.LiveRelationApi(courseID, knowledgeID, jobID, aid, 3, nil)
}

var xxtLiveSaveTimeProvider = func(c *xxtmobile.XxtClient, streamName, vdoID, userID, courseID string) (string, error) {
	return c.LiveSaveTimePcApi(streamName, vdoID, userID, courseID, 3, nil)
}

var xxtBbsInfoProvider = func(c *xxtmobile.XxtClient, mid, jobID, knowledgeID, courseID, classID string) (string, error) {
	return c.PullPhoneBbsInfoApi(mid, jobID, knowledgeID, courseID, classID, 3, nil)
}

var xxtBbsDetailProvider = func(c *xxtmobile.XxtClient, topicID string) (string, error) {
	return c.PullPhoneBbsDetailApi(topicID, 3, nil)
}

var xxtBbsAnswerProvider = func(c *xxtmobile.XxtClient, classID, topicUUID, content string) (string, error) {
	return c.AnswerPhoneBbsApi(classID, topicUUID, content, 3, nil)
}

var xxtMonitorProvider = func(c *xxtmobile.XxtClient) (string, error) {
	return c.MonitorApi()
}

var xxtBbsUtEncProvider = func(c *xxtmobile.XxtClient, courseID, classID, knowledgeID, enc string) (string, error) {
	return c.PullBbsUtEncApi(courseID, classID, knowledgeID, enc, 3, nil)
}

var xxtBbsCircleProvider = func(c *xxtmobile.XxtClient, mid, jobID, knowledgeID, classID, enc, utEnc, courseID string, isJob bool) (string, string, error) {
	return c.PullBbsCircleIdApi(mid, jobID, knowledgeID, classID, enc, utEnc, courseID, isJob, 3, nil)
}

var xxtBbsWebInfoProvider = func(c *xxtmobile.XxtClient, id1, id2, courseID, classID string) (string, error) {
	return c.PullBbsInfoApi(id1, id2, courseID, classID, 3, nil)
}

var xxtBbsWebAnswerProvider = func(c *xxtmobile.XxtClient, topicUUID, courseID, classID, content, urlToken, bbsID string) (string, error) {
	return c.AnswerWebBbsApi(topicUUID, courseID, classID, content, urlToken, bbsID, 3, nil)
}

var xxtChapterProvider = func(c *xxtmobile.XxtClient, cpi, key int) (string, error) {
	return c.PullChapterApi(cpi, key, 3, nil)
}

// --- Login ---

func loginXuexitong(input AccountInput) (SessionData, error) {
	if len(input.Password) >= 50 {
		return SessionData{
			Platform: "xuexitong",
			Account:  input.Account,
			Cookies:  input.Password,
			Extra: map[string]interface{}{
				"loginMode": "cookie",
			},
		}, nil
	}
	c := &xxtmobile.XxtClient{Name: input.Account, Password: input.Password}
	raw, err := xxtLoginProvider(c)
	if err != nil {
		return SessionData{}, fmt.Errorf("xuexitong: login request failed: %w", err)
	}
	var resp struct {
		Status bool   `json:"status"`
		Msg2   string `json:"msg2"`
	}
	if err := json.Unmarshal([]byte(raw), &resp); err != nil {
		return SessionData{}, fmt.Errorf("xuexitong: login parse error: %w", err)
	}
	if !resp.Status {
		if strings.Contains(resp.Msg2, "验证码") {
			return SessionData{}, fmt.Errorf("xuexitong: login requires captcha; use StartLogin when OCR adapter is available")
		}
		msg := resp.Msg2
		if msg == "" {
			msg = raw
		}
		return SessionData{}, fmt.Errorf("xuexitong: login failed: %s", msg)
	}
	return SessionData{
		Platform: "xuexitong",
		Account:  input.Account,
		Cookies:  cookieSliceToStr(c.Cookies),
		Extra: map[string]interface{}{
			"loginMode": "pc_http",
		},
	}, nil
}

// --- GetCourses ---

func getCoursesXuexitong(sess SessionData) (CourseListResult, error) {
	c := xxtClient(sess)
	raw, err := xxtCourseListProvider(c)
	if err != nil {
		return CourseListResult{}, fmt.Errorf("xuexitong: courses request failed: %w", err)
	}
	return xxtParseCourses(raw)
}

func xxtParseCourses(raw string) (CourseListResult, error) {
	var resp struct {
		Result      int `json:"result"`
		ChannelList []struct {
			Cpi     json.Number `json:"cpi"`
			Key     interface{} `json:"key"`
			Content struct {
				Chatid string `json:"chatid"`
				Course struct {
					Data []struct {
						Id              int    `json:"id"`
						Name            string `json:"name"`
						Imageurl        string `json:"imageurl"`
						CourseSquareUrl string `json:"courseSquareUrl"`
					} `json:"data"`
				} `json:"course"`
			} `json:"content"`
		} `json:"channelList"`
	}
	if err := json.Unmarshal([]byte(raw), &resp); err != nil {
		return CourseListResult{}, fmt.Errorf("xuexitong: courses parse error: %w", err)
	}
	if resp.Result != 1 {
		return CourseListResult{}, fmt.Errorf("xuexitong: courses failed: %s", raw)
	}
	items := make([]CourseItem, 0)
	for _, ch := range resp.ChannelList {
		if len(ch.Content.Course.Data) == 0 {
			continue
		}
		d := ch.Content.Course.Data[0]
		classId := urlParam(d.CourseSquareUrl, "classId")
		courseId := urlParam(d.CourseSquareUrl, "courseId")
		userId := urlParam(d.CourseSquareUrl, "userId")
		keyStr := fmt.Sprintf("%v", ch.Key)
		if classId == "" {
			classId = keyStr
		}
		items = append(items, CourseItem{
			ID:    courseId,
			Name:  d.Name,
			Cover: d.Imageurl,
			Raw: map[string]interface{}{
				"courseId": courseId,
				"classId":  classId,
				"cpi":      ch.Cpi.String(),
				"chatId":   ch.Content.Chatid,
				"userId":   userId,
				"key":      keyStr,
			},
		})
	}
	return CourseListResult{Platform: "xuexitong", Courses: items}, nil
}

// --- GetCourseDetail ---

func getCourseDetailXuexitong(sess SessionData, input CourseInput) (CourseDetailResult, error) {
	courseId := input.ID
	if courseId == "" {
		courseId = strOf(input.Raw["courseId"])
	}
	if courseId == "" {
		return CourseDetailResult{}, fmt.Errorf("xuexitong: courseJSON.id (or raw.courseId) is required")
	}
	cpi := xxtIntParam(input.Raw, "cpi")
	key := xxtIntParam(input.Raw, "key")
	if key == 0 {
		key = xxtIntParam(input.Raw, "classId")
	}
	if cpi == 0 || key == 0 {
		return CourseDetailResult{}, fmt.Errorf("xuexitong: courseJSON.raw.cpi and key/classId are required")
	}
	c := xxtClient(sess)
	raw, err := xxtChapterProvider(c, cpi, key)
	if err != nil {
		return CourseDetailResult{}, fmt.Errorf("xuexitong: course detail failed: %w", err)
	}
	result, err := xxtParseChapter(raw, courseId, strconv.Itoa(key), strconv.Itoa(cpi))
	if err != nil {
		return CourseDetailResult{}, err
	}
	// Best-effort: annotate each node with task-point completion so getTasks can skip
	// already-finished nodes without fetching their cards (mirrors console ChapterFetchPointAction).
	xxtAnnotatePointStatus(sess, &result, input, courseId, key, cpi)
	return result, nil
}

// xxtAnnotatePointStatus queries job/myjobsnodesmap once for the whole course and writes
// each node's pointTotal/pointFinished into its Raw. It is best-effort: any missing userId
// or request/parse failure leaves nodes unannotated, so getTasks falls back to fetching cards.
func xxtAnnotatePointStatus(sess SessionData, result *CourseDetailResult, input CourseInput, courseId string, key, cpi int) {
	userIdStr := strOf(input.Raw["userId"])
	if userIdStr == "" {
		userIdStr = strOf(sess.Extra["userId"])
	}
	userID, err := strconv.Atoi(userIdStr)
	if err != nil || userID == 0 {
		return
	}
	courseID, err := strconv.Atoi(courseId)
	if err != nil {
		return
	}
	nodes := make([]int, 0, len(result.Items))
	for _, it := range result.Items {
		if id, err := strconv.Atoi(it.ID); err == nil {
			nodes = append(nodes, id)
		}
	}
	if len(nodes) == 0 {
		return
	}
	raw, err := xxtPointStatusProvider(xxtClient(sess), nodes, key, userID, cpi, courseID)
	if err != nil {
		return
	}
	status, err := xxtmobile.ParseNodePointStatus(raw)
	if err != nil || len(status) == 0 {
		return
	}
	for i := range result.Items {
		id, err := strconv.Atoi(result.Items[i].ID)
		if err != nil {
			continue
		}
		if st, ok := status[id]; ok {
			result.Items[i].Raw["pointTotal"] = st.Total
			result.Items[i].Raw["pointFinished"] = st.Finished
		}
	}
}

func xxtParseChapter(raw, courseId, classId, cpi string) (CourseDetailResult, error) {
	if err := xxtRejectNonJSONCourseDetail(raw); err != nil {
		return CourseDetailResult{}, err
	}
	var resp struct {
		Data []struct {
			Course struct {
				Data []struct {
					Knowledge struct {
						Data []xxtKnowledgeNode `json:"data"`
					} `json:"knowledge"`
				} `json:"data"`
			} `json:"course"`
		} `json:"data"`
		Course struct {
			Data []struct {
				Knowledge struct {
					Data []xxtKnowledgeNode `json:"data"`
				} `json:"knowledge"`
			} `json:"data"`
		} `json:"course"`
	}
	if err := json.Unmarshal([]byte(raw), &resp); err != nil {
		return CourseDetailResult{}, fmt.Errorf("xuexitong: course detail parse error: %w", err)
	}
	courseData := resp.Course.Data
	if len(courseData) == 0 && len(resp.Data) > 0 {
		courseData = resp.Data[0].Course.Data
	}
	if len(courseData) == 0 {
		return CourseDetailResult{}, fmt.Errorf("xuexitong: 没拿到课程章节数据")
	}
	items := make([]CourseItem, 0)
	for _, cd := range courseData {
		for _, k := range cd.Knowledge.Data {
			atts := make([]map[string]string, 0, len(k.Attachment.Data))
			for _, a := range k.Attachment.Data {
				atts = append(atts, map[string]string{"id": a.Id, "type": a.Type, "objectid": a.Objectid, "extension": a.Extension})
			}
			items = append(items, CourseItem{
				ID:   strconv.Itoa(k.Id),
				Name: k.Name,
				Raw: map[string]interface{}{
					"courseId":     courseId,
					"classId":      classId,
					"cpi":          cpi,
					"knowledgeId":  k.Id,
					"parentNodeId": k.Parentnodeid,
					"layer":        k.Layer,
					"jobCount":     k.Jobcount,
					"attachments":  atts,
				},
			})
		}
	}
	if len(items) == 0 {
		return CourseDetailResult{}, fmt.Errorf("xuexitong: 没拿到课程章节数据")
	}
	return CourseDetailResult{Platform: "xuexitong", ParentID: courseId, Items: items}, nil
}

type xxtKnowledgeNode struct {
	Id           int    `json:"id"`
	Name         string `json:"name"`
	Parentnodeid int    `json:"parentnodeid"`
	Layer        int    `json:"layer"`
	Jobcount     int    `json:"jobcount"`
	Attachment   struct {
		Data []struct {
			Id        string `json:"id"`
			Type      string `json:"type"`
			Objectid  string `json:"objectid"`
			Extension string `json:"extension"`
		} `json:"data"`
	} `json:"attachment"`
}

func xxtRejectNonJSONCourseDetail(raw string) error {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return fmt.Errorf("xuexitong: 课程详情没有返回数据")
	}
	if strings.HasPrefix(trimmed, "<") {
		return xxtCourseDetailPageError(trimmed)
	}
	lower := strings.ToLower(trimmed)
	if strings.Contains(lower, "<html") || strings.Contains(lower, "<!doctype") {
		return xxtCourseDetailPageError(trimmed)
	}
	return nil
}

func xxtCourseDetailPageError(raw string) error {
	title := xxtHTMLTitle(raw)
	switch {
	case strings.Contains(raw, "登录") || strings.Contains(raw, "login") || strings.Contains(raw, "请先登录"):
		if title != "" {
			return fmt.Errorf("xuexitong: 课程详情返回了登录页：%s；请重新登录学习通", title)
		}
		return fmt.Errorf("xuexitong: 课程详情返回了登录页；请重新登录学习通")
	case strings.Contains(raw, "验证码") || strings.Contains(raw, "validate"):
		if title != "" {
			return fmt.Errorf("xuexitong: 课程详情触发了验证码：%s", title)
		}
		return fmt.Errorf("xuexitong: 课程详情触发了验证码")
	case strings.Contains(raw, "很抱歉") || strings.Contains(raw, "暂时不能访问") || strings.Contains(raw, "页面不存在"):
		if title != "" {
			return fmt.Errorf("xuexitong: 学习通暂时拒绝访问课程详情：%s", title)
		}
		return fmt.Errorf("xuexitong: 学习通暂时拒绝访问课程详情")
	default:
		if title != "" {
			return fmt.Errorf("xuexitong: 课程详情返回了网页，不是课程数据：%s", title)
		}
		return fmt.Errorf("xuexitong: 课程详情返回了网页，不是课程数据；登录状态可能已失效")
	}
}

func xxtHTMLTitle(raw string) string {
	re := regexp.MustCompile(`(?is)<title[^>]*>(.*?)</title>`)
	m := re.FindStringSubmatch(raw)
	if len(m) < 2 {
		return ""
	}
	title := strings.Join(strings.Fields(m[1]), " ")
	if len([]rune(title)) > 40 {
		return string([]rune(title)[:40])
	}
	return title
}

// --- GetTasks ---

func getTasksXuexitong(sess SessionData, input CourseInput) (TaskListResult, error) {
	courseId := strOf(input.Raw["courseId"])
	classId := strOf(input.Raw["classId"])
	cpi := strOf(input.Raw["cpi"])
	knowledgeId := numToStr(input.Raw["knowledgeId"])
	if courseId == "" || classId == "" || cpi == "" {
		return TaskListResult{}, fmt.Errorf("xuexitong: courseJSON.raw must include courseId, classId, cpi")
	}
	// Primary: card-based discovery (gas/knowledge → iframe task points), matching the
	// console. The chapter tree's inline attachment field is unreliable (often empty and
	// missing jobId), so we only fall back to it when the cards fetch/parse fails.
	if knowledgeId != "" {
		// Skip nodes getCourseDetail already flagged as fully complete — no card fetch needed.
		if xxtNodeAlreadyComplete(input.Raw) {
			return TaskListResult{Platform: "xuexitong", ParentID: input.ID, Tasks: []TaskItem{}}, nil
		}
		if tasks, ok := xxtTasksFromCards(sess, courseId, classId, cpi, knowledgeId); ok {
			return TaskListResult{Platform: "xuexitong", ParentID: input.ID, Tasks: tasks}, nil
		}
	}
	return TaskListResult{
		Platform: "xuexitong",
		ParentID: input.ID,
		Tasks:    xxtTasksFromAttachments(input, courseId, classId, cpi, knowledgeId),
	}, nil
}

// xxtTasksFromCards discovers task points for one knowledge node via the cards API.
// Returns (tasks, true) whenever the fetch+parse succeeds (even with zero points, so a
// genuine container node yields no tasks); returns (nil, false) only on error so the
// caller can fall back to the legacy attachment source.
func xxtTasksFromCards(sess SessionData, courseId, classId, cpi, knowledgeId string) ([]TaskItem, bool) {
	nodeID, err1 := strconv.Atoi(knowledgeId)
	courseID, err2 := strconv.Atoi(courseId)
	if err1 != nil || err2 != nil {
		return nil, false
	}
	c := xxtClient(sess)
	raw, err := xxtKnowledgeCardsProvider(c, nodeID, courseID)
	if err != nil {
		return nil, false
	}
	points, err := xxtmobile.ParseKnowledgePoints(raw)
	if err != nil {
		return nil, false
	}
	tasks := make([]TaskItem, 0, len(points))
	for _, p := range points {
		taskType := xxtPointType(p.Module)
		if !xxtKnownTaskType(taskType) {
			continue
		}
		taskID := p.ObjectID
		if taskID == "" {
			taskID = p.JobID
		}
		if taskID == "" {
			taskID = p.WorkID
		}
		if taskID == "" {
			continue
		}
		raw := map[string]interface{}{
			"courseId":    courseId,
			"classId":     classId,
			"cpi":         cpi,
			"knowledgeId": knowledgeId,
			"objectId":    p.ObjectID,
			"module":      p.Module,
			"cardIndex":   p.CardIndex,
		}
		if p.JobID != "" {
			raw["jobId"] = p.JobID
		}
		if p.WorkID != "" {
			raw["workId"] = p.WorkID
		}
		if p.SchoolID != "" {
			raw["schoolId"] = p.SchoolID
		}
		name := p.Title
		if name == "" {
			name = p.Module
		}
		tasks = append(tasks, TaskItem{ID: taskID, Name: name, Type: taskType, Raw: raw})
	}
	return tasks, true
}

// xxtTasksFromAttachments is the legacy discovery path: it reads task points from the
// chapter tree's inline attachment data (input.Raw["attachments"]). Kept as a fallback
// for when the cards API is unavailable.
func xxtTasksFromAttachments(input CourseInput, courseId, classId, cpi, knowledgeId string) []TaskItem {
	tasks := make([]TaskItem, 0)
	attsRaw, ok := input.Raw["attachments"]
	if !ok {
		return tasks
	}
	atts, ok := attsRaw.([]interface{})
	if !ok {
		return tasks
	}
	for _, a := range atts {
		m, ok := a.(map[string]interface{})
		if !ok {
			continue
		}
		id, _ := m["id"].(string)
		typ, _ := m["type"].(string)
		objectid, _ := m["objectid"].(string)
		extension, _ := m["extension"].(string)
		taskID := objectid
		if taskID == "" {
			taskID = id
		}
		if taskID == "" {
			continue
		}
		tasks = append(tasks, TaskItem{
			ID:   taskID,
			Name: typ,
			Type: xxtPointType(typ),
			Raw: map[string]interface{}{
				"courseId":     courseId,
				"classId":      classId,
				"cpi":          cpi,
				"knowledgeId":  knowledgeId,
				"objectId":     objectid,
				"attachmentId": id,
				"extension":    extension,
			},
		})
	}
	return tasks
}

// xxtKnownTaskType reports whether a resolved point type is one we can schedule.
// Mirrors the handled cases in the console's ChapterFetchCardsAction switch.
func xxtKnownTaskType(t string) bool {
	switch t {
	case "video", "audio", "document", "read", "live", "bbs", "quiz", "hyperlink":
		return true
	}
	return false
}

// xxtNodeAlreadyComplete reports whether a node was flagged fully complete by the
// point-status pre-filter (getCourseDetail). When the status is absent it returns false,
// so getTasks fetches the cards as usual. Total==Finished also covers a zero-point
// container node (0==0), which has nothing to schedule.
func xxtNodeAlreadyComplete(raw map[string]interface{}) bool {
	total, ok1 := xxtRawInt(raw, "pointTotal")
	finished, ok2 := xxtRawInt(raw, "pointFinished")
	if !ok1 || !ok2 {
		return false
	}
	return total == finished
}

// xxtRawInt reads an int from a Raw field, tolerating the float64/json.Number/string
// forms it can take after a JSON round-trip. The bool reports whether the field existed.
func xxtRawInt(raw map[string]interface{}, key string) (int, bool) {
	v, ok := raw[key]
	if !ok || v == nil {
		return 0, false
	}
	switch t := v.(type) {
	case float64:
		return int(t), true
	case int:
		return t, true
	case int64:
		return int(t), true
	case json.Number:
		n, _ := t.Int64()
		return int(n), true
	case string:
		n, err := strconv.Atoi(t)
		if err != nil {
			return 0, false
		}
		return n, true
	}
	return 0, false
}

func xxtPointType(t string) string {
	switch t {
	case "video", "insertvideo":
		return "video"
	case "document", "insertdoc":
		return "document"
	case "read", "insertreadV2":
		return "read"
	case "insertbook":
		return "document"
	case "audio", "insertaudio":
		return "audio"
	case "live", "insertlive":
		return "live"
	case "bbs", "insertbbs":
		return "bbs"
	case "work", "workquiz":
		return "quiz"
	default:
		return t
	}
}

// xxtIntParam reads an int from a raw map field (handles float64 and string).
func xxtIntParam(raw map[string]interface{}, key string) int {
	v := raw[key]
	if v == nil {
		return 0
	}
	switch t := v.(type) {
	case float64:
		return int(t)
	case string:
		n, _ := strconv.Atoi(t)
		return n
	case json.Number:
		n, _ := t.Int64()
		return int(n)
	}
	return 0
}

// urlParam extracts a query parameter value from a URL string.
func urlParam(rawURL, key string) string {
	idx := strings.Index(rawURL, "?")
	if idx >= 0 {
		rawURL = rawURL[idx+1:]
	}
	v, _ := url.ParseQuery(rawURL)
	return v.Get(key)
}

// --- RunTask ---

func runTaskXuexitong(sess SessionData, input TaskInput) (RunTaskResult, error) {
	switch strOf(input.Options["action"]) {
	case "pullWorkList":
		return xxtPullWorkList(sess, input)
	case "enterWork":
		return xxtEnterWork(sess, input)
	case "workQuestion":
		return xxtWorkQuestion(sess, input)
	case "work":
		return xxtSubmitWork(sess, input)
	case "pullExamList":
		return xxtPullExamList(sess, input)
	case "enterExam":
		return xxtEnterExam(sess, input)
	case "examQuestion":
		return xxtExamQuestion(sess, input)
	case "exam":
		return xxtSubmitExam(sess, input)
	case "pullChapterTest":
		return xxtPullChapterTest(sess, input)
	case "chapterTest":
		return xxtSubmitChapterTest(sess, input)
	case "passChapterCaptcha":
		return xxtPassChapterCaptcha(sess, input)
	case "xxtAI":
		return xxtRunAI(sess, input)
	case "videoPrepare":
		return xxtVideoPrepare(sess, input)
	case "videoTick", "video":
		return xxtVideoTick(sess, input)
	case "audioPrepare":
		return xxtAudioPrepare(sess, input)
	case "audioTick", "audio":
		return xxtAudioTick(sess, input)
	case "hyperlinkPrepare":
		return xxtHyperlinkPrepare(sess, input)
	case "hyperlink":
		return xxtHyperlinkSubmit(sess, input)
	case "documentPrepare", "readPrepare":
		return xxtDocumentPrepare(sess, input)
	case "document", "read", "readv2":
		return xxtDocumentSubmit(sess, input)
	case "livePrepare":
		return xxtLivePrepare(sess, input)
	case "liveTick", "live":
		return xxtLiveTick(sess, input)
	case "bbsPrepare", "pullBbs":
		return xxtBbsPrepare(sess, input)
	case "bbs":
		return xxtBbsSubmit(sess, input)
	case "monitor", "keepAlive":
		return xxtMonitor(sess, input)
	case "bbsWebPrepare", "pullBbsWeb":
		return xxtBbsWebPrepare(sess, input)
	case "bbsWeb":
		return xxtBbsWebSubmit(sess, input)
	}
	objectID := input.ID
	if objectID == "" {
		objectID = strOf(input.Raw["objectId"])
	}
	if objectID == "" {
		return RunTaskResult{}, fmt.Errorf("xuexitong: taskJSON.id (or raw.objectId) is required")
	}
	courseId := strOf(input.Raw["courseId"])
	if courseId == "" {
		return RunTaskResult{}, fmt.Errorf("xuexitong: taskJSON.raw.courseId is required")
	}
	classId := strOf(input.Raw["classId"])
	if classId == "" {
		return RunTaskResult{}, fmt.Errorf("xuexitong: taskJSON.raw.classId is required")
	}
	cpi := strOf(input.Raw["cpi"])
	if cpi == "" {
		return RunTaskResult{}, fmt.Errorf("xuexitong: taskJSON.raw.cpi is required")
	}
	knowledgeId := numToStr(input.Raw["knowledgeId"])
	if knowledgeId == "" {
		return RunTaskResult{}, fmt.Errorf("xuexitong: taskJSON.raw.knowledgeId is required")
	}

	if dryRun, _ := input.Options["dryRun"].(bool); dryRun {
		return RunTaskResult{
			Platform: "xuexitong", TaskID: objectID, Status: "dry_run",
			Message: fmt.Sprintf("objectId=%s classId=%s cpi=%s knowledgeId=%s", objectID, classId, cpi, knowledgeId),
		}, nil
	}

	prepared, err := prepareXxtMedia(sess, input, "video")
	if err != nil {
		return RunTaskResult{}, err
	}
	if prepared.IsPassedKnown && prepared.IsPassed {
		return RunTaskResult{Platform: "xuexitong", TaskID: prepared.ObjectID, Status: "done", Raw: xxtMediaRaw(prepared, "video", prepared.PlayTime, prepared.PlayTime >= prepared.Duration)}, nil
	}

	// Legacy one-shot callers submit the end position once. The result remains "progress"
	// unless the server explicitly confirms isPassed=true; Android uses prepare/tick instead.
	playingTime := prepared.Duration
	c := xxtClient(sess)
	submitRaw, err := xxtSubmitStudyProvider(c, xxtmobile.VideoSubmitParams{
		ClassID: prepared.ClassID, UserID: prepared.UserID, JobID: prepared.JobID,
		ObjectID: prepared.ObjectID, CourseID: prepared.CourseID, CPI: prepared.CPI,
		DToken: prepared.DToken, OtherInfo: prepared.OtherInfo,
		VideoFaceCaptureEnc: prepared.VideoFaceCaptureEnc, AttDurationEnc: prepared.AttDurationEnc,
		PlayingTime: playingTime, Duration: prepared.Duration, IsDrag: 0, RT: prepared.RT,
	})
	if err != nil {
		return RunTaskResult{}, fmt.Errorf("xuexitong: submit study time failed: %w", err)
	}
	result, err := xxtParseMediaSubmit(submitRaw)
	if err != nil {
		return RunTaskResult{}, err
	}
	prepared.IsPassed = result.IsPassed
	prepared.IsPassedKnown = true
	raw := xxtMediaRaw(prepared, "video", playingTime, true)
	raw["realSubmit"] = true
	raw["response"] = submitRaw
	status := "progress"
	if result.IsPassed {
		status = "done"
	}
	return RunTaskResult{Platform: "xuexitong", TaskID: prepared.ObjectID, Status: status, Raw: raw}, nil
}

type xxtMediaPrepared struct {
	ObjectID            string
	CourseID            string
	ClassID             string
	CPI                 string
	KnowledgeID         string
	UserID              string
	JobID               string
	OtherInfo           string
	DToken              string
	Title               string
	Mid                 string
	AttDurationEnc      string
	VideoFaceCaptureEnc string
	RandomCaptureTime   string
	FID                 int
	Duration            int
	PlayTime            int
	RT                  float64
	IsPassed            bool
	IsPassedKnown       bool
	IsJob               bool
	IsJobKnown          bool
}

func xxtVideoPrepare(sess SessionData, input TaskInput) (RunTaskResult, error) {
	prepared, err := prepareXxtMedia(sess, input, "video")
	if err != nil {
		return RunTaskResult{}, err
	}
	status := xxtMediaPreparedStatus(prepared)
	return RunTaskResult{
		Platform: "xuexitong",
		TaskID:   prepared.ObjectID,
		Status:   status,
		Message:  "video prepared; host should submit a start heartbeat then timed progress heartbeats",
		Raw:      xxtMediaRaw(prepared, "video", prepared.PlayTime, prepared.Duration > 0 && prepared.PlayTime >= prepared.Duration),
	}, nil
}

func xxtVideoTick(sess SessionData, input TaskInput) (RunTaskResult, error) {
	prepared, err := prepareXxtMedia(sess, input, "video")
	if err != nil {
		return RunTaskResult{}, err
	}
	playingTime := optInt(input.Options["playingTime"], optInt(input.Options["progress"], optInt(input.Options["currentTime"], 58)))
	if playingTime > prepared.Duration && prepared.Duration > 0 {
		playingTime = prepared.Duration
	}
	reachedEnd := prepared.Duration > 0 && playingTime >= prepared.Duration
	raw := xxtMediaRaw(prepared, "video", playingTime, reachedEnd)
	raw["realSubmit"] = false
	if !xxtShouldSubmit(input, true) {
		return RunTaskResult{Platform: "xuexitong", TaskID: prepared.ObjectID, Status: "dry_run", Message: "xuexitong video dry-run", Raw: raw}, nil
	}
	if !xxtProgressProvided(input) {
		return RunTaskResult{}, fmt.Errorf("xuexitong: action=videoTick realSubmit requires options.playingTime/progress/currentTime")
	}
	isdrag := optInt(input.Options["isdrag"], 0)
	c := xxtClient(sess)
	resp, err := xxtSubmitStudyProvider(c, xxtmobile.VideoSubmitParams{
		ClassID: prepared.ClassID, UserID: prepared.UserID, JobID: prepared.JobID,
		ObjectID: prepared.ObjectID, CourseID: prepared.CourseID, CPI: prepared.CPI,
		DToken: prepared.DToken, OtherInfo: prepared.OtherInfo,
		VideoFaceCaptureEnc: prepared.VideoFaceCaptureEnc, AttDurationEnc: prepared.AttDurationEnc,
		PlayingTime: playingTime, Duration: prepared.Duration, IsDrag: isdrag, RT: prepared.RT,
	})
	if err != nil {
		return RunTaskResult{}, fmt.Errorf("xuexitong: video submit failed: %w", err)
	}
	result, err := xxtParseMediaSubmit(resp)
	if err != nil {
		return RunTaskResult{}, err
	}
	prepared.IsPassed = result.IsPassed
	prepared.IsPassedKnown = true
	raw = xxtMediaRaw(prepared, "video", playingTime, reachedEnd)
	raw["realSubmit"] = true
	raw["response"] = resp
	if result.OutTimeMsg != "" {
		raw["outTimeMsg"] = result.OutTimeMsg
	}
	status := "progress"
	if result.IsPassed {
		status = "done"
	}
	return RunTaskResult{Platform: "xuexitong", TaskID: prepared.ObjectID, Status: status, Raw: raw}, nil
}

func xxtAudioPrepare(sess SessionData, input TaskInput) (RunTaskResult, error) {
	prepared, err := prepareXxtMedia(sess, input, "audio")
	if err != nil {
		return RunTaskResult{}, err
	}
	status := xxtMediaPreparedStatus(prepared)
	return RunTaskResult{
		Platform: "xuexitong",
		TaskID:   prepared.ObjectID,
		Status:   status,
		Message:  "audio prepared; host should submit a start heartbeat then timed progress heartbeats",
		Raw:      xxtMediaRaw(prepared, "audio", prepared.PlayTime, prepared.Duration > 0 && prepared.PlayTime >= prepared.Duration),
	}, nil
}

func xxtAudioTick(sess SessionData, input TaskInput) (RunTaskResult, error) {
	prepared, err := prepareXxtMedia(sess, input, "audio")
	if err != nil {
		return RunTaskResult{}, err
	}
	playingTime := optInt(input.Options["playingTime"], optInt(input.Options["progress"], optInt(input.Options["currentTime"], 58)))
	if playingTime > prepared.Duration && prepared.Duration > 0 {
		playingTime = prepared.Duration
	}
	reachedEnd := prepared.Duration > 0 && playingTime >= prepared.Duration
	raw := xxtMediaRaw(prepared, "audio", playingTime, reachedEnd)
	raw["realSubmit"] = false
	if !xxtShouldSubmit(input, true) {
		return RunTaskResult{Platform: "xuexitong", TaskID: prepared.ObjectID, Status: "dry_run", Message: "xuexitong audio dry-run", Raw: raw}, nil
	}
	if !xxtProgressProvided(input) {
		return RunTaskResult{}, fmt.Errorf("xuexitong: action=audioTick realSubmit requires options.playingTime/progress/currentTime")
	}
	isdrag := optInt(input.Options["isdrag"], 0)
	c := xxtClient(sess)
	resp, err := xxtAudioSubmitProvider(c, prepared.ClassID, prepared.UserID, prepared.JobID, prepared.ObjectID, prepared.CourseID, prepared.OtherInfo, playingTime, prepared.Duration, isdrag)
	if err != nil {
		return RunTaskResult{}, fmt.Errorf("xuexitong: audio submit failed: %w", err)
	}
	result, err := xxtParseMediaSubmit(resp)
	if err != nil {
		return RunTaskResult{}, err
	}
	prepared.IsPassed = result.IsPassed
	prepared.IsPassedKnown = true
	raw = xxtMediaRaw(prepared, "audio", playingTime, reachedEnd)
	raw["realSubmit"] = true
	raw["response"] = resp
	if result.OutTimeMsg != "" {
		raw["outTimeMsg"] = result.OutTimeMsg
	}
	status := "progress"
	if result.IsPassed {
		status = "done"
	}
	return RunTaskResult{Platform: "xuexitong", TaskID: prepared.ObjectID, Status: status, Raw: raw}, nil
}

func prepareXxtMedia(sess SessionData, input TaskInput, kind string) (xxtMediaPrepared, error) {
	objectID := input.ID
	if objectID == "" {
		objectID = strOf(input.Raw["objectId"])
	}
	courseID := strOf(input.Raw["courseId"])
	classID := strOf(input.Raw["classId"])
	cpi := strOf(input.Raw["cpi"])
	knowledgeID := numToStr(input.Raw["knowledgeId"])
	for field, val := range map[string]string{
		"objectId": objectID, "courseId": courseID, "classId": classID, "cpi": cpi, "knowledgeId": knowledgeID,
	} {
		if val == "" {
			return xxtMediaPrepared{}, fmt.Errorf("xuexitong: taskJSON.raw.%s is required for %s", field, kind)
		}
	}

	p := xxtMediaPrepared{
		ObjectID: objectID, CourseID: courseID, ClassID: classID, CPI: cpi, KnowledgeID: knowledgeID,
		UserID: strOf(input.Raw["userId"]), JobID: strOf(input.Raw["jobId"]), OtherInfo: strOf(input.Raw["otherInfo"]),
		DToken: strOf(input.Raw["dtoken"]), Title: strOf(input.Raw["title"]), Mid: strOf(input.Raw["mid"]),
		AttDurationEnc: strOf(input.Raw["attDurationEnc"]), VideoFaceCaptureEnc: strOf(input.Raw["videoFaceCaptureEnc"]),
		RandomCaptureTime: strOf(input.Raw["randomCaptureTime"]), FID: xxtIntParam(input.Raw, "fid"),
		Duration: optInt(input.Raw["duration"], 0), PlayTime: optInt(input.Raw["playingTime"], optInt(input.Raw["playTime"], 0)),
		RT: optFloatDefault(input.Raw["rt"], 0.9),
	}
	if _, ok := input.Raw["isPassed"]; ok {
		p.IsPassed = optBool(input.Raw["isPassed"], false)
		p.IsPassedKnown = optBool(input.Raw["isPassedKnown"], true)
	}
	if _, ok := input.Raw["isJob"]; ok {
		p.IsJob = optBool(input.Raw["isJob"], false)
		p.IsJobKnown = optBool(input.Raw["isJobKnown"], true)
	}
	if p.UserID == "" {
		p.UserID = strOf(sess.Extra["userId"])
	}
	if p.UserID == "" {
		p.UserID = xxtClient(sess).UserID
	}

	action := strOf(input.Options["action"])
	if p.JobID == "" || p.OtherInfo == "" || action == kind+"Prepare" {
		c := xxtClient(sess)
		cardHTML, err := xxtCardProvider(c, classID, courseID, knowledgeID, cpi)
		if err != nil {
			return xxtMediaPrepared{}, fmt.Errorf("xuexitong: %s card fetch failed: %w", kind, err)
		}
		meta, err := xxtmobile.ParseCardHTMLForVideoTask(cardHTML, objectID)
		if err != nil {
			return xxtMediaPrepared{}, fmt.Errorf("xuexitong: %s card parse failed: %w", kind, err)
		}
		if p.JobID == "" {
			p.JobID = meta.JobID
		}
		if p.ObjectID == "" {
			p.ObjectID = meta.ObjectID
		}
		if p.OtherInfo == "" {
			p.OtherInfo = meta.OtherInfo
		}
		if p.UserID == "" {
			p.UserID = meta.PUID
		}
		if meta.FID > 0 {
			p.FID = meta.FID
		}
		if p.Title == "" {
			p.Title = meta.Title
		}
		if p.Mid == "" {
			p.Mid = meta.Mid
		}
		if p.AttDurationEnc == "" {
			p.AttDurationEnc = meta.AttDurationEnc
		}
		if p.VideoFaceCaptureEnc == "" {
			p.VideoFaceCaptureEnc = meta.VideoFaceCaptureEnc
		}
		if p.RandomCaptureTime == "" {
			p.RandomCaptureTime = meta.RandomCaptureTime
		}
		if meta.PlayTime > 0 || p.PlayTime == 0 {
			p.PlayTime = meta.PlayTime
		}
		if meta.AttDuration > 0 && p.Duration <= 0 {
			p.Duration = meta.AttDuration
		}
		if meta.RT > 0 {
			p.RT = meta.RT
		}
		if meta.IsPassedKnown {
			p.IsPassed = meta.IsPassed
			p.IsPassedKnown = true
		}
		if meta.IsJobKnown {
			p.IsJob = meta.IsJob
			p.IsJobKnown = true
		}
	}

	needDTO := p.Duration <= 0 || (kind == "video" && p.DToken == "")
	if needDTO {
		fid := p.FID
		if fid == 0 {
			// Legacy fallback for callers that bypass videoPrepare; real card flows use defaults.fid.
			fid, _ = strconv.Atoi(classID)
		}
		c := xxtClient(sess)
		dtoRaw, err := xxtVideoDtoProvider(c, p.ObjectID, fid)
		if err != nil {
			return xxtMediaPrepared{}, fmt.Errorf("xuexitong: %s dto fetch failed: %w", kind, err)
		}
		dto, err := xxtmobile.ParseVideoDtoMeta(dtoRaw)
		if err != nil {
			return xxtMediaPrepared{}, fmt.Errorf("xuexitong: %s dto parse failed: %w", kind, err)
		}
		if p.DToken == "" {
			p.DToken = dto.DToken
		}
		if p.Duration <= 0 {
			p.Duration = dto.Duration
		}
	}
	if p.JobID == "" {
		return xxtMediaPrepared{}, fmt.Errorf("xuexitong: card HTML missing jobId")
	}
	if p.UserID == "" {
		return xxtMediaPrepared{}, fmt.Errorf("xuexitong: %s userid is required", kind)
	}
	if p.Duration <= 0 {
		return xxtMediaPrepared{}, fmt.Errorf("xuexitong: %s duration is invalid", kind)
	}
	if kind == "video" && p.DToken == "" {
		return xxtMediaPrepared{}, fmt.Errorf("xuexitong: video dtoken is required")
	}
	if p.RT <= 0 {
		p.RT = 0.9
	}
	return p, nil
}

func xxtMediaPreparedStatus(p xxtMediaPrepared) string {
	if p.IsPassedKnown && p.IsPassed {
		return "done"
	}
	if p.IsJobKnown && !p.IsJob {
		return "skipped"
	}
	return "prepared"
}

func xxtMediaRaw(p xxtMediaPrepared, mode string, playingTime int, reachedEnd bool) map[string]interface{} {
	raw := map[string]interface{}{
		"submitMode": mode, "intervalSeconds": 58,
		"objectId": p.ObjectID, "courseId": p.CourseID, "classId": p.ClassID, "cpi": p.CPI,
		"knowledgeId": p.KnowledgeID, "userId": p.UserID, "jobId": p.JobID, "otherInfo": p.OtherInfo,
		"duration": p.Duration, "playingTime": playingTime, "reachedEnd": reachedEnd,
		"isPassed": p.IsPassed, "isPassedKnown": p.IsPassedKnown, "isFinish": p.IsPassedKnown && p.IsPassed,
		"isJob": p.IsJob, "isJobKnown": p.IsJobKnown, "fid": p.FID, "rt": p.RT,
		"title": p.Title, "mid": p.Mid, "attDurationEnc": p.AttDurationEnc,
		"videoFaceCaptureEnc": p.VideoFaceCaptureEnc, "randomCaptureTime": p.RandomCaptureTime,
	}
	if p.DToken != "" {
		raw["dtoken"] = p.DToken
	}
	return raw
}

type xxtMediaSubmitResult struct {
	IsPassed   bool
	OutTimeMsg string
}

func xxtParseMediaSubmit(raw string) (xxtMediaSubmitResult, error) {
	var resp map[string]interface{}
	if err := json.Unmarshal([]byte(raw), &resp); err != nil {
		return xxtMediaSubmitResult{}, fmt.Errorf("xuexitong: media submit invalid response: %s", xxtResponseSnippet(raw))
	}
	if result, ok := resp["result"].(float64); ok && result == 0 {
		return xxtMediaSubmitResult{}, fmt.Errorf("xuexitong: media submit failed: %s", xxtResponseSnippet(raw))
	}
	passed, ok := resp["isPassed"].(bool)
	if !ok {
		return xxtMediaSubmitResult{}, fmt.Errorf("xuexitong: media submit response missing isPassed: %s", xxtResponseSnippet(raw))
	}
	outTimeMsg, _ := resp["OutTimeMsg"].(string)
	if outTimeMsg == "" {
		outTimeMsg, _ = resp["outTimeMsg"].(string)
	}
	return xxtMediaSubmitResult{IsPassed: passed, OutTimeMsg: outTimeMsg}, nil
}

func xxtProgressProvided(input TaskInput) bool {
	for _, v := range []interface{}{input.Options["playingTime"], input.Options["progress"], input.Options["currentTime"]} {
		if _, ok := optFloat(v); ok {
			return true
		}
	}
	return false
}

type xxtHyperlinkPrepared struct {
	ObjectID    string
	CourseID    string
	ClassID     string
	CPI         string
	KnowledgeID string
	JobID       string
	JToken      string
	Title       string
}

func xxtHyperlinkPrepare(sess SessionData, input TaskInput) (RunTaskResult, error) {
	prepared, err := prepareXxtHyperlink(sess, input)
	if err != nil {
		return RunTaskResult{}, err
	}
	return RunTaskResult{
		Platform: "xuexitong",
		TaskID:   prepared.taskID(),
		Status:   "prepared",
		Message:  "hyperlink prepared; host may call action=hyperlink with realSubmit=true",
		Raw:      xxtHyperlinkRaw(prepared, false, ""),
	}, nil
}

func xxtHyperlinkSubmit(sess SessionData, input TaskInput) (RunTaskResult, error) {
	prepared, err := prepareXxtHyperlink(sess, input)
	if err != nil {
		return RunTaskResult{}, err
	}
	raw := xxtHyperlinkRaw(prepared, false, "")
	if !xxtShouldSubmit(input, true) {
		return RunTaskResult{
			Platform: "xuexitong",
			TaskID:   prepared.taskID(),
			Status:   "dry_run",
			Message:  "xuexitong hyperlink dry-run",
			Raw:      raw,
		}, nil
	}
	c := xxtClient(sess)
	resp, err := xxtHyperlinkCompleteProvider(c, prepared.JobID, prepared.KnowledgeID, prepared.CourseID, prepared.ClassID, prepared.JToken)
	if err != nil {
		return RunTaskResult{}, fmt.Errorf("xuexitong: hyperlink submit failed: %w", err)
	}
	raw = xxtHyperlinkRaw(prepared, true, resp)
	if err := xxtValidateCompletionSubmit("hyperlink", resp); err != nil {
		return RunTaskResult{}, err
	}
	return RunTaskResult{
		Platform: "xuexitong",
		TaskID:   prepared.taskID(),
		Status:   "submitted",
		Raw:      raw,
	}, nil
}

func prepareXxtHyperlink(sess SessionData, input TaskInput) (xxtHyperlinkPrepared, error) {
	objectID := input.ID
	if objectID == "" {
		objectID = strOf(input.Raw["objectId"])
	}
	courseId := strOf(input.Raw["courseId"])
	classId := strOf(input.Raw["classId"])
	cpi := strOf(input.Raw["cpi"])
	knowledgeId := numToStr(input.Raw["knowledgeId"])
	for field, val := range map[string]string{
		"courseId": courseId, "classId": classId, "cpi": cpi, "knowledgeId": knowledgeId,
	} {
		if val == "" {
			return xxtHyperlinkPrepared{}, fmt.Errorf("xuexitong: taskJSON.raw.%s is required for hyperlink", field)
		}
	}
	jobID := optString(input.Options["jobId"], strOf(input.Raw["jobId"]))
	if jobID == "" {
		jobID = optString(input.Options["jobID"], strOf(input.Raw["jobID"]))
	}
	jtoken := optString(input.Options["jtoken"], strOf(input.Raw["jtoken"]))
	title := optString(input.Options["title"], strOf(input.Raw["title"]))
	if jobID == "" || jtoken == "" {
		c := xxtClient(sess)
		cardHTML, err := xxtCardProvider(c, classId, courseId, knowledgeId, cpi)
		if err != nil {
			return xxtHyperlinkPrepared{}, fmt.Errorf("xuexitong: hyperlink card fetch failed: %w", err)
		}
		meta, err := xxtmobile.ParseCardHTMLForHyperlinkTask(cardHTML, jobID, objectID)
		if err != nil {
			return xxtHyperlinkPrepared{}, fmt.Errorf("xuexitong: hyperlink card parse failed: %w", err)
		}
		if jobID == "" {
			jobID = meta.JobID
		}
		if objectID == "" {
			objectID = meta.ObjectID
		}
		if jtoken == "" {
			jtoken = meta.JToken
		}
		if title == "" {
			title = meta.Title
		}
	}
	if jobID == "" {
		return xxtHyperlinkPrepared{}, fmt.Errorf("xuexitong: taskJSON.raw.jobId is required for hyperlink")
	}
	if jtoken == "" {
		return xxtHyperlinkPrepared{}, fmt.Errorf("xuexitong: taskJSON.raw.jtoken is required for hyperlink")
	}
	return xxtHyperlinkPrepared{
		ObjectID: objectID, CourseID: courseId, ClassID: classId, CPI: cpi, KnowledgeID: knowledgeId,
		JobID: jobID, JToken: jtoken, Title: title,
	}, nil
}

func (p xxtHyperlinkPrepared) taskID() string {
	if p.ObjectID != "" {
		return p.ObjectID
	}
	return p.JobID
}

func xxtHyperlinkRaw(p xxtHyperlinkPrepared, realSubmit bool, response string) map[string]interface{} {
	raw := map[string]interface{}{
		"submitMode":  "hyperlink",
		"objectId":    p.ObjectID,
		"courseId":    p.CourseID,
		"classId":     p.ClassID,
		"cpi":         p.CPI,
		"knowledgeId": p.KnowledgeID,
		"jobId":       p.JobID,
		"jtoken":      p.JToken,
		"title":       p.Title,
		"realSubmit":  realSubmit,
	}
	if response != "" {
		raw["response"] = response
	}
	return raw
}

func xxtValidateCompletionSubmit(kind, raw string) error {
	var resp struct {
		Status *bool  `json:"status"`
		Msg    string `json:"msg"`
	}
	if err := json.Unmarshal([]byte(raw), &resp); err != nil {
		return fmt.Errorf("xuexitong: %s submit invalid response: %s", kind, xxtResponseSnippet(raw))
	}
	if resp.Status == nil {
		return fmt.Errorf("xuexitong: %s submit response missing status: %s", kind, xxtResponseSnippet(raw))
	}
	if !*resp.Status {
		msg := strings.TrimSpace(resp.Msg)
		if msg == "" {
			msg = xxtResponseSnippet(raw)
		}
		return fmt.Errorf("xuexitong: %s submit rejected: %s", kind, msg)
	}
	return nil
}

func xxtResponseSnippet(raw string) string {
	const maxRunes = 500
	clean := strings.TrimSpace(raw)
	runes := []rune(clean)
	if len(runes) > maxRunes {
		clean = string(runes[:maxRunes]) + "…"
	}
	return clean
}

type xxtDocumentPrepared struct {
	ObjectID     string
	CourseID     string
	ClassID      string
	CPI          string
	KnowledgeID  string
	JobID        string
	JToken       string
	Title        string
	DocumentType string
	IsJob        bool
}

func xxtDocumentPrepare(sess SessionData, input TaskInput) (RunTaskResult, error) {
	prepared, err := prepareXxtDocument(sess, input)
	if err != nil {
		return RunTaskResult{}, err
	}
	return RunTaskResult{
		Platform: "xuexitong",
		TaskID:   prepared.taskID(),
		Status:   "prepared",
		Message:  "document prepared; host may call action=document with realSubmit=true",
		Raw:      xxtDocumentRaw(prepared, false, ""),
	}, nil
}

func xxtDocumentSubmit(sess SessionData, input TaskInput) (RunTaskResult, error) {
	prepared, err := prepareXxtDocument(sess, input)
	if err != nil {
		return RunTaskResult{}, err
	}
	raw := xxtDocumentRaw(prepared, false, "")
	if !xxtShouldSubmit(input, true) {
		return RunTaskResult{
			Platform: "xuexitong",
			TaskID:   prepared.taskID(),
			Status:   "dry_run",
			Message:  "xuexitong document dry-run",
			Raw:      raw,
		}, nil
	}
	c := xxtClient(sess)
	resp, err := xxtDocumentCompleteProvider(c, prepared.JobID, prepared.KnowledgeID, prepared.CourseID, prepared.ClassID, prepared.JToken, prepared.DocumentType)
	if err != nil {
		return RunTaskResult{}, fmt.Errorf("xuexitong: document submit failed: %w", err)
	}
	raw = xxtDocumentRaw(prepared, true, resp)
	if err := xxtValidateCompletionSubmit("document", resp); err != nil {
		return RunTaskResult{}, err
	}
	return RunTaskResult{Platform: "xuexitong", TaskID: prepared.taskID(), Status: "submitted", Raw: raw}, nil
}

func prepareXxtDocument(sess SessionData, input TaskInput) (xxtDocumentPrepared, error) {
	objectID := input.ID
	if objectID == "" {
		objectID = strOf(input.Raw["objectId"])
	}
	courseId := strOf(input.Raw["courseId"])
	classId := strOf(input.Raw["classId"])
	cpi := strOf(input.Raw["cpi"])
	knowledgeId := numToStr(input.Raw["knowledgeId"])
	for field, val := range map[string]string{
		"courseId": courseId, "classId": classId, "cpi": cpi, "knowledgeId": knowledgeId,
	} {
		if val == "" {
			return xxtDocumentPrepared{}, fmt.Errorf("xuexitong: taskJSON.raw.%s is required for document", field)
		}
	}
	documentType := xxtDocumentType(input)
	jobID := optString(input.Options["jobId"], strOf(input.Raw["jobId"]))
	if jobID == "" {
		jobID = optString(input.Options["jobID"], strOf(input.Raw["jobID"]))
	}
	jtoken := optString(input.Options["jtoken"], strOf(input.Raw["jtoken"]))
	title := optString(input.Options["title"], strOf(input.Raw["title"]))
	isJob := optBool(input.Raw["isJob"], false)
	if jobID == "" || jtoken == "" {
		c := xxtClient(sess)
		cardHTML, err := xxtCardProvider(c, classId, courseId, knowledgeId, cpi)
		if err != nil {
			return xxtDocumentPrepared{}, fmt.Errorf("xuexitong: document card fetch failed: %w", err)
		}
		meta, err := xxtmobile.ParseCardHTMLForDocumentTask(cardHTML, jobID, objectID, documentType)
		if err != nil {
			return xxtDocumentPrepared{}, fmt.Errorf("xuexitong: document card parse failed: %w", err)
		}
		if jobID == "" {
			jobID = meta.JobID
		}
		if objectID == "" {
			objectID = meta.ObjectID
		}
		if jtoken == "" {
			jtoken = meta.JToken
		}
		if title == "" {
			title = meta.Title
		}
		if !isJob {
			isJob = meta.IsJob
		}
		if strOf(input.Raw["documentType"]) == "" && strOf(input.Options["documentType"]) == "" && meta.DocumentType != "" {
			documentType = meta.DocumentType
		}
	}
	if jobID == "" {
		return xxtDocumentPrepared{}, fmt.Errorf("xuexitong: taskJSON.raw.jobId is required for document")
	}
	if jtoken == "" {
		return xxtDocumentPrepared{}, fmt.Errorf("xuexitong: taskJSON.raw.jtoken is required for document")
	}
	return xxtDocumentPrepared{
		ObjectID: objectID, CourseID: courseId, ClassID: classId, CPI: cpi, KnowledgeID: knowledgeId,
		JobID: jobID, JToken: jtoken, Title: title, DocumentType: documentType, IsJob: isJob,
	}, nil
}

func xxtDocumentType(input TaskInput) string {
	t := optString(input.Options["documentType"], strOf(input.Raw["documentType"]))
	if t == "" {
		t = optString(input.Options["module"], strOf(input.Raw["module"]))
	}
	if t == "" {
		t = input.Type
	}
	action := strOf(input.Options["action"])
	if t == "" && (action == "read" || action == "readPrepare" || action == "readv2") {
		t = "insertreadV2"
	}
	switch t {
	case "insertbook", "book":
		return "insertbook"
	case "insertreadV2", "insertreadv2", "readv2", "read":
		return "insertreadV2"
	default:
		return "document"
	}
}

func (p xxtDocumentPrepared) taskID() string {
	if p.ObjectID != "" {
		return p.ObjectID
	}
	return p.JobID
}

func xxtDocumentRaw(p xxtDocumentPrepared, realSubmit bool, response string) map[string]interface{} {
	raw := map[string]interface{}{
		"submitMode":   "document",
		"documentType": p.DocumentType,
		"objectId":     p.ObjectID,
		"courseId":     p.CourseID,
		"classId":      p.ClassID,
		"cpi":          p.CPI,
		"knowledgeId":  p.KnowledgeID,
		"jobId":        p.JobID,
		"jtoken":       p.JToken,
		"title":        p.Title,
		"isJob":        p.IsJob,
		"realSubmit":   realSubmit,
	}
	if response != "" {
		raw["response"] = response
	}
	return raw
}

func xxtShouldSubmit(input TaskInput, def bool) bool {
	if optBool(input.Options["dryRun"], false) {
		return false
	}
	if v, ok := input.Options["realSubmit"]; ok {
		return optBool(v, def)
	}
	return def
}

type xxtLivePrepared struct {
	CourseID     string
	ClassID      string
	CPI          string
	KnowledgeID  string
	UserID       string
	JobID        string
	LiveID       string
	StreamName   string
	VdoID        string
	AID          string
	Title        string
	LiveStatus   string
	Percent      float64
	Duration     int
	StatusCode   int
	RelationResp string
	InfoResp     string
}

func xxtLivePrepare(sess SessionData, input TaskInput) (RunTaskResult, error) {
	prepared, err := prepareXxtLive(sess, input)
	if err != nil {
		return RunTaskResult{}, err
	}
	c := xxtClient(sess)
	infoRaw, err := xxtLiveInfoProvider(c, prepared.LiveID, prepared.UserID, prepared.ClassID, prepared.KnowledgeID, prepared.CourseID, prepared.JobID)
	if err != nil {
		return RunTaskResult{}, fmt.Errorf("xuexitong: live info failed: %w", err)
	}
	prepared.applyInfo(infoRaw)
	raw := xxtLiveRaw(prepared, false, "")
	if prepared.StatusCode == 0 {
		return RunTaskResult{Platform: "xuexitong", TaskID: prepared.JobID, Status: "skipped", Message: "live has not started", Raw: raw}, nil
	}
	if prepared.Percent >= 90 {
		return RunTaskResult{Platform: "xuexitong", TaskID: prepared.JobID, Status: "done", Raw: raw}, nil
	}
	if !xxtShouldSubmit(input, true) {
		return RunTaskResult{Platform: "xuexitong", TaskID: prepared.JobID, Status: "dry_run", Message: "xuexitong live prepare dry-run", Raw: raw}, nil
	}
	relationRaw, err := xxtLiveRelationProvider(c, prepared.CourseID, prepared.KnowledgeID, prepared.JobID, prepared.AID)
	if err != nil {
		return RunTaskResult{}, fmt.Errorf("xuexitong: live relation failed: %w", err)
	}
	prepared.RelationResp = relationRaw
	return RunTaskResult{
		Platform: "xuexitong",
		TaskID:   prepared.JobID,
		Status:   "prepared",
		Message:  "live prepared; host should call action=liveTick every 30s until raw.percent >= 90",
		Raw:      xxtLiveRaw(prepared, true, ""),
	}, nil
}

func xxtLiveTick(sess SessionData, input TaskInput) (RunTaskResult, error) {
	prepared, err := prepareXxtLive(sess, input)
	if err != nil {
		return RunTaskResult{}, err
	}
	raw := xxtLiveRaw(prepared, false, "")
	if !xxtShouldSubmit(input, true) {
		return RunTaskResult{Platform: "xuexitong", TaskID: prepared.JobID, Status: "dry_run", Message: "xuexitong live tick dry-run", Raw: raw}, nil
	}
	c := xxtClient(sess)
	saveRaw, err := xxtLiveSaveTimeProvider(c, prepared.StreamName, prepared.VdoID, prepared.UserID, prepared.CourseID)
	if err != nil {
		return RunTaskResult{}, fmt.Errorf("xuexitong: live saveTime failed: %w", err)
	}
	infoRaw, err := xxtLiveInfoProvider(c, prepared.LiveID, prepared.UserID, prepared.ClassID, prepared.KnowledgeID, prepared.CourseID, prepared.JobID)
	if err != nil {
		return RunTaskResult{}, fmt.Errorf("xuexitong: live info failed: %w", err)
	}
	prepared.applyInfo(infoRaw)
	raw = xxtLiveRaw(prepared, true, saveRaw)
	if !strings.Contains(saveRaw, "@success") {
		return RunTaskResult{Platform: "xuexitong", TaskID: prepared.JobID, Status: "rejected", Message: saveRaw, Raw: raw}, nil
	}
	if prepared.Percent >= 90 {
		return RunTaskResult{Platform: "xuexitong", TaskID: prepared.JobID, Status: "done", Raw: raw}, nil
	}
	return RunTaskResult{Platform: "xuexitong", TaskID: prepared.JobID, Status: "submitted", Raw: raw}, nil
}

func prepareXxtLive(sess SessionData, input TaskInput) (xxtLivePrepared, error) {
	courseId := strOf(input.Raw["courseId"])
	classId := strOf(input.Raw["classId"])
	cpi := strOf(input.Raw["cpi"])
	knowledgeId := numToStr(input.Raw["knowledgeId"])
	for field, val := range map[string]string{
		"courseId": courseId, "classId": classId, "cpi": cpi, "knowledgeId": knowledgeId,
	} {
		if val == "" {
			return xxtLivePrepared{}, fmt.Errorf("xuexitong: taskJSON.raw.%s is required for live", field)
		}
	}
	p := xxtLivePrepared{
		CourseID: courseId, ClassID: classId, CPI: cpi, KnowledgeID: knowledgeId,
		UserID:     optString(input.Options["userId"], strOf(input.Raw["userId"])),
		JobID:      optString(input.Options["jobId"], strOf(input.Raw["jobId"])),
		LiveID:     optString(input.Options["liveId"], strOf(input.Raw["liveId"])),
		StreamName: optString(input.Options["streamName"], strOf(input.Raw["streamName"])),
		VdoID:      optString(input.Options["vdoid"], strOf(input.Raw["vdoid"])),
		AID:        optString(input.Options["aid"], strOf(input.Raw["aid"])),
		Title:      optString(input.Options["title"], strOf(input.Raw["title"])),
		LiveStatus: optString(input.Options["liveStatus"], strOf(input.Raw["liveStatus"])),
		Percent:    optFloatDefault(input.Raw["percent"], 0),
		Duration:   optInt(input.Raw["duration"], 0),
		StatusCode: optInt(input.Raw["statusCode"], 0),
	}
	if p.UserID == "" {
		p.UserID = strOf(sess.Extra["userId"])
	}
	if p.JobID == "" || p.LiveID == "" || p.StreamName == "" || p.VdoID == "" || p.UserID == "" {
		c := xxtClient(sess)
		cardHTML, err := xxtCardProvider(c, classId, courseId, knowledgeId, cpi)
		if err != nil {
			return xxtLivePrepared{}, fmt.Errorf("xuexitong: live card fetch failed: %w", err)
		}
		meta, err := xxtmobile.ParseCardHTMLForLiveTask(cardHTML, p.JobID)
		if err != nil {
			return xxtLivePrepared{}, fmt.Errorf("xuexitong: live card parse failed: %w", err)
		}
		if p.JobID == "" {
			p.JobID = meta.JobID
		}
		if p.LiveID == "" {
			p.LiveID = meta.LiveID
		}
		if p.UserID == "" {
			p.UserID = meta.UserID
		}
		if p.StreamName == "" {
			p.StreamName = meta.StreamName
		}
		if p.VdoID == "" {
			p.VdoID = meta.VdoID
		}
		if p.AID == "" {
			p.AID = meta.AID
		}
		if p.Title == "" {
			p.Title = meta.Title
		}
		if p.LiveStatus == "" {
			p.LiveStatus = meta.LiveStatusStr
		}
	}
	for field, val := range map[string]string{
		"jobId": p.JobID, "liveId": p.LiveID, "userId": p.UserID, "streamName": p.StreamName, "vdoid": p.VdoID,
	} {
		if val == "" {
			return xxtLivePrepared{}, fmt.Errorf("xuexitong: taskJSON.raw.%s is required for live", field)
		}
	}
	return p, nil
}

func (p *xxtLivePrepared) applyInfo(raw string) {
	p.InfoResp = raw
	if info, err := xxtmobile.ParseLiveInfo(raw); err == nil {
		p.Percent = info.Percent
		p.Duration = info.Duration
		p.StatusCode = info.StatusCode
	}
}

func xxtLiveRaw(p xxtLivePrepared, realSubmit bool, response string) map[string]interface{} {
	raw := map[string]interface{}{
		"submitMode":      "live",
		"intervalSeconds": 30,
		"courseId":        p.CourseID,
		"classId":         p.ClassID,
		"cpi":             p.CPI,
		"knowledgeId":     p.KnowledgeID,
		"userId":          p.UserID,
		"jobId":           p.JobID,
		"liveId":          p.LiveID,
		"streamName":      p.StreamName,
		"vdoid":           p.VdoID,
		"aid":             p.AID,
		"title":           p.Title,
		"liveStatus":      p.LiveStatus,
		"percent":         p.Percent,
		"duration":        p.Duration,
		"statusCode":      p.StatusCode,
		"isFinish":        p.Percent >= 90,
		"realSubmit":      realSubmit,
	}
	if p.RelationResp != "" {
		raw["relationResponse"] = p.RelationResp
	}
	if p.InfoResp != "" {
		raw["infoResponse"] = p.InfoResp
	}
	if response != "" {
		raw["response"] = response
	}
	return raw
}

func optFloatDefault(v interface{}, def float64) float64 {
	if f, ok := optFloat(v); ok {
		return f
	}
	return def
}

type xxtBbsPrepared struct {
	CourseID       string
	ClassID        string
	CPI            string
	KnowledgeID    string
	JobID          string
	Mid            string
	Title          string
	Detail         string
	Module         string
	OtherInfo      string
	Enc            string
	AuthEnc        string
	AllowViewReply int
	ReplyTimes     string
	ReplyWordNum   string
	EndTime        string
	IsJob          bool
	Topic          xxtmobile.BbsTopic
	InfoResp       string
	DetailResp     string
}

func xxtBbsPrepare(sess SessionData, input TaskInput) (RunTaskResult, error) {
	prepared, err := prepareXxtBbs(sess, input, true)
	if err != nil {
		return RunTaskResult{}, err
	}
	return RunTaskResult{
		Platform: "xuexitong",
		TaskID:   prepared.JobID,
		Status:   "prepared",
		Message:  "bbs topic prepared; host should pass reply text as options.content to action=bbs",
		Raw:      xxtBbsRaw(prepared, false, ""),
	}, nil
}

func xxtBbsSubmit(sess SessionData, input TaskInput) (RunTaskResult, error) {
	content := optString(input.Options["content"], optString(input.Options["answer"], optString(input.Options["reply"], "")))
	if strings.TrimSpace(content) == "" {
		return RunTaskResult{}, fmt.Errorf("xuexitong: action=bbs requires options.content/answer/reply from host")
	}
	prepared, err := prepareXxtBbs(sess, input, false)
	if err != nil {
		return RunTaskResult{}, err
	}
	raw := xxtBbsRaw(prepared, false, "")
	raw["content"] = content
	if !xxtShouldSubmit(input, true) {
		return RunTaskResult{Platform: "xuexitong", TaskID: prepared.JobID, Status: "dry_run", Message: "xuexitong bbs dry-run", Raw: raw}, nil
	}
	classID := prepared.Topic.ClassID
	if classID == "" {
		classID = prepared.ClassID
	}
	topicUUID := prepared.Topic.TopicUUID
	if topicUUID == "" {
		topicUUID = optString(input.Options["topicUUID"], strOf(input.Raw["topicUUID"]))
	}
	if topicUUID == "" {
		return RunTaskResult{}, fmt.Errorf("xuexitong: bbs topicUUID is required")
	}
	c := xxtClient(sess)
	resp, err := xxtBbsAnswerProvider(c, classID, topicUUID, content)
	if err != nil {
		return RunTaskResult{}, fmt.Errorf("xuexitong: bbs submit failed: %w", err)
	}
	raw = xxtBbsRaw(prepared, true, resp)
	raw["content"] = content
	ok, msg := xxtmobile.ParseBbsSubmit(resp, "phone")
	if !ok {
		if msg == "" {
			msg = resp
		}
		return RunTaskResult{Platform: "xuexitong", TaskID: prepared.JobID, Status: "rejected", Message: msg, Raw: raw}, nil
	}
	return RunTaskResult{Platform: "xuexitong", TaskID: prepared.JobID, Status: "submitted", Raw: raw}, nil
}

func prepareXxtBbs(sess SessionData, input TaskInput, fetchTopic bool) (xxtBbsPrepared, error) {
	return prepareXxtBbsBase(sess, input, fetchTopic, true)
}

func prepareXxtBbsBase(sess SessionData, input TaskInput, fetchTopic, fetchMissingTopic bool) (xxtBbsPrepared, error) {
	courseId := strOf(input.Raw["courseId"])
	classId := strOf(input.Raw["classId"])
	cpi := strOf(input.Raw["cpi"])
	knowledgeId := numToStr(input.Raw["knowledgeId"])
	for field, val := range map[string]string{
		"courseId": courseId, "classId": classId, "cpi": cpi, "knowledgeId": knowledgeId,
	} {
		if val == "" {
			return xxtBbsPrepared{}, fmt.Errorf("xuexitong: taskJSON.raw.%s is required for bbs", field)
		}
	}
	p := xxtBbsPrepared{
		CourseID: courseId, ClassID: classId, CPI: cpi, KnowledgeID: knowledgeId,
		JobID:          optString(input.Options["jobId"], strOf(input.Raw["jobId"])),
		Mid:            optString(input.Options["mid"], strOf(input.Raw["mid"])),
		Title:          optString(input.Options["title"], strOf(input.Raw["title"])),
		Detail:         optString(input.Options["detail"], strOf(input.Raw["detail"])),
		Module:         optString(input.Options["module"], strOf(input.Raw["module"])),
		OtherInfo:      optString(input.Options["otherInfo"], strOf(input.Raw["otherInfo"])),
		Enc:            optString(input.Options["enc"], strOf(input.Raw["enc"])),
		AuthEnc:        optString(input.Options["authEnc"], strOf(input.Raw["authEnc"])),
		AllowViewReply: optInt(input.Raw["allowViewReply"], 0),
		ReplyTimes:     strOf(input.Raw["replyTimes"]),
		ReplyWordNum:   strOf(input.Raw["replyWordNum"]),
		EndTime:        strOf(input.Raw["endTime"]),
		IsJob:          optBool(input.Raw["isJob"], false),
	}
	if p.JobID == "" || p.Mid == "" {
		c := xxtClient(sess)
		cardHTML, err := xxtCardProvider(c, classId, courseId, knowledgeId, cpi)
		if err != nil {
			return xxtBbsPrepared{}, fmt.Errorf("xuexitong: bbs card fetch failed: %w", err)
		}
		meta, err := xxtmobile.ParseCardHTMLForBbsTask(cardHTML, p.JobID)
		if err != nil {
			return xxtBbsPrepared{}, fmt.Errorf("xuexitong: bbs card parse failed: %w", err)
		}
		if p.JobID == "" {
			p.JobID = meta.JobID
		}
		if p.Mid == "" {
			p.Mid = meta.Mid
		}
		if p.Title == "" {
			p.Title = meta.Title
		}
		if p.Detail == "" {
			p.Detail = meta.Detail
		}
		if p.Module == "" {
			p.Module = meta.Module
		}
		if p.OtherInfo == "" {
			p.OtherInfo = meta.OtherInfo
		}
		if p.Enc == "" {
			p.Enc = meta.Enc
		}
		if p.AuthEnc == "" {
			p.AuthEnc = meta.AuthEnc
		}
		if p.AllowViewReply == 0 {
			p.AllowViewReply = meta.AllowViewReply
		}
		if p.ReplyTimes == "" {
			p.ReplyTimes = meta.ReplyTimes
		}
		if p.ReplyWordNum == "" {
			p.ReplyWordNum = meta.ReplyWordNum
		}
		if p.EndTime == "" {
			p.EndTime = meta.EndTime
		}
		if !p.IsJob {
			p.IsJob = meta.IsJob
		}
	}
	if p.JobID == "" || p.Mid == "" {
		return xxtBbsPrepared{}, fmt.Errorf("xuexitong: taskJSON.raw.jobId and raw.mid are required for bbs")
	}
	topic := xxtmobile.BbsTopic{
		Platform:  optString(input.Options["platform"], strOf(input.Raw["bbsPlatform"])),
		TopicID:   optString(input.Options["topicId"], strOf(input.Raw["topicId"])),
		TopicUUID: optString(input.Options["topicUUID"], strOf(input.Raw["topicUUID"])),
		ClassID:   optString(input.Options["topicClassId"], strOf(input.Raw["topicClassId"])),
		CourseID:  optString(input.Options["topicCourseId"], strOf(input.Raw["topicCourseId"])),
		Title:     optString(input.Options["topicTitle"], strOf(input.Raw["topicTitle"])),
		Content:   optString(input.Options["topicContent"], strOf(input.Raw["topicContent"])),
		BbsID:     optString(input.Options["bbsId"], strOf(input.Raw["bbsId"])),
		GroupID:   optString(input.Options["groupId"], strOf(input.Raw["groupId"])),
	}
	if topic.Platform == "" {
		topic.Platform = "phone"
	}
	p.Topic = topic
	if fetchTopic || (fetchMissingTopic && p.Topic.TopicUUID == "") {
		c := xxtClient(sess)
		infoHTML, err := xxtBbsInfoProvider(c, p.Mid, p.JobID, p.KnowledgeID, p.CourseID, p.ClassID)
		if err != nil {
			return xxtBbsPrepared{}, fmt.Errorf("xuexitong: bbs info failed: %w", err)
		}
		p.InfoResp = infoHTML
		topicID := p.Topic.TopicID
		if topicID == "" {
			topicID = xxtBbsTopicIDFromHTML(infoHTML)
		}
		if topicID == "" {
			return xxtBbsPrepared{}, fmt.Errorf("xuexitong: bbs topicId not found")
		}
		detailRaw, err := xxtBbsDetailProvider(c, topicID)
		if err != nil {
			return xxtBbsPrepared{}, fmt.Errorf("xuexitong: bbs detail failed: %w", err)
		}
		p.DetailResp = detailRaw
		topic, err := xxtmobile.ParsePhoneBbsTopic(infoHTML, detailRaw)
		if err != nil {
			return xxtBbsPrepared{}, fmt.Errorf("xuexitong: bbs topic parse failed: %w", err)
		}
		p.Topic = topic
	}
	return p, nil
}

func xxtBbsTopicIDFromHTML(html string) string {
	re := regexp.MustCompile(`<input[^>]+id=["']topicId["'][^>]+value=["']([^"']+)["']`)
	if m := re.FindStringSubmatch(html); len(m) > 1 {
		return m[1]
	}
	return ""
}

func xxtBbsRaw(p xxtBbsPrepared, realSubmit bool, response string) map[string]interface{} {
	raw := map[string]interface{}{
		"submitMode":      "bbs",
		"courseId":        p.CourseID,
		"classId":         p.ClassID,
		"cpi":             p.CPI,
		"knowledgeId":     p.KnowledgeID,
		"jobId":           p.JobID,
		"mid":             p.Mid,
		"title":           p.Title,
		"detail":          p.Detail,
		"module":          p.Module,
		"otherInfo":       p.OtherInfo,
		"enc":             p.Enc,
		"authEnc":         p.AuthEnc,
		"allowViewReply":  p.AllowViewReply,
		"replyTimes":      p.ReplyTimes,
		"replyWordNum":    p.ReplyWordNum,
		"endTime":         p.EndTime,
		"isJob":           p.IsJob,
		"topic":           p.Topic,
		"prompt":          strings.TrimSpace(p.Topic.Title + "\n" + p.Topic.Content),
		"answerSource":    "host",
		"realSubmit":      realSubmit,
		"requiresHostAI":  true,
		"requiresHostOCR": false,
	}
	if p.InfoResp != "" {
		raw["infoResponse"] = p.InfoResp
	}
	if p.DetailResp != "" {
		raw["detailResponse"] = p.DetailResp
	}
	if response != "" {
		raw["response"] = response
	}
	return raw
}

func xxtMonitor(sess SessionData, input TaskInput) (RunTaskResult, error) {
	raw := map[string]interface{}{
		"submitMode":      "monitor",
		"intervalSeconds": 300,
		"realSubmit":      false,
	}
	if !xxtShouldSubmit(input, true) {
		return RunTaskResult{Platform: "xuexitong", TaskID: strOf(input.ID), Status: "dry_run", Message: "xuexitong monitor dry-run", Raw: raw}, nil
	}
	c := xxtClient(sess)
	resp, err := xxtMonitorProvider(c)
	if err != nil {
		return RunTaskResult{}, fmt.Errorf("xuexitong: monitor failed: %w", err)
	}
	alive, expired := xxtmobile.ParseMonitorResult(resp)
	raw["realSubmit"] = true
	raw["alive"] = alive
	raw["expired"] = expired
	raw["response"] = resp
	status := "submitted"
	if alive {
		status = "alive"
	} else if expired {
		status = "expired"
	}
	return RunTaskResult{Platform: "xuexitong", TaskID: strOf(input.ID), Status: status, Raw: raw}, nil
}

func xxtBbsWebPrepare(sess SessionData, input TaskInput) (RunTaskResult, error) {
	prepared, err := prepareXxtBbsWeb(sess, input, true)
	if errors.Is(err, xxtmobile.ErrBbsCaptcha) {
		return xxtBbsWebCaptcha(sess, input)
	}
	if err != nil {
		return RunTaskResult{}, err
	}
	return RunTaskResult{
		Platform: "xuexitong",
		TaskID:   prepared.JobID,
		Status:   "prepared",
		Message:  "bbs web topic prepared; host should pass reply text as options.content to action=bbsWeb",
		Raw:      xxtBbsWebRaw(prepared, false, ""),
	}, nil
}

func xxtBbsWebSubmit(sess SessionData, input TaskInput) (RunTaskResult, error) {
	content := optString(input.Options["content"], optString(input.Options["answer"], optString(input.Options["reply"], "")))
	if strings.TrimSpace(content) == "" {
		return RunTaskResult{}, fmt.Errorf("xuexitong: action=bbsWeb requires options.content/answer/reply from host")
	}
	prepared, err := prepareXxtBbsWeb(sess, input, false)
	if errors.Is(err, xxtmobile.ErrBbsCaptcha) {
		return xxtBbsWebCaptcha(sess, input)
	}
	if err != nil {
		return RunTaskResult{}, err
	}
	raw := xxtBbsWebRaw(prepared, false, "")
	raw["content"] = content
	if !xxtShouldSubmit(input, true) {
		return RunTaskResult{Platform: "xuexitong", TaskID: prepared.JobID, Status: "dry_run", Message: "xuexitong bbs web dry-run", Raw: raw}, nil
	}
	if prepared.Topic.TopicUUID == "" || prepared.Topic.URLToken == "" || prepared.Topic.BbsID == "" {
		return RunTaskResult{}, fmt.Errorf("xuexitong: bbs web requires topicUUID, urlToken, bbsId")
	}
	c := xxtClient(sess)
	resp, err := xxtBbsWebAnswerProvider(c, prepared.Topic.TopicUUID, prepared.CourseID, prepared.ClassID, content, prepared.Topic.URLToken, prepared.Topic.BbsID)
	if err != nil {
		return RunTaskResult{}, fmt.Errorf("xuexitong: bbs web submit failed: %w", err)
	}
	raw = xxtBbsWebRaw(prepared, true, resp)
	raw["content"] = content
	ok, msg := xxtmobile.ParseBbsSubmit(resp, "web")
	if !ok {
		if msg == "" {
			msg = resp
		}
		return RunTaskResult{Platform: "xuexitong", TaskID: prepared.JobID, Status: "rejected", Message: msg, Raw: raw}, nil
	}
	return RunTaskResult{Platform: "xuexitong", TaskID: prepared.JobID, Status: "submitted", Raw: raw}, nil
}

func prepareXxtBbsWeb(sess SessionData, input TaskInput, fetchTopic bool) (xxtBbsPrepared, error) {
	p, err := prepareXxtBbsBase(sess, input, false, false)
	if err != nil {
		return xxtBbsPrepared{}, err
	}
	p.Topic.Platform = "web"
	p.Topic.TopicUUID = optString(input.Options["topicUUID"], optString(input.Options["uuid"], strOf(input.Raw["topicUUID"])))
	p.Topic.URLToken = optString(input.Options["urlToken"], strOf(input.Raw["urlToken"]))
	p.Topic.BbsID = optString(input.Options["bbsId"], strOf(input.Raw["bbsId"]))
	p.Topic.Title = optString(input.Options["topicTitle"], strOf(input.Raw["topicTitle"]))
	p.Topic.Content = optString(input.Options["topicContent"], strOf(input.Raw["topicContent"]))
	if !fetchTopic && p.Topic.TopicUUID != "" && p.Topic.URLToken != "" && p.Topic.BbsID != "" {
		return p, nil
	}
	if p.Enc == "" {
		return xxtBbsPrepared{}, fmt.Errorf("xuexitong: bbs web requires raw.enc from card attachment or task raw")
	}
	c := xxtClient(sess)
	utEnc, err := xxtBbsUtEncProvider(c, p.CourseID, p.ClassID, p.KnowledgeID, p.Enc)
	if err != nil {
		return xxtBbsPrepared{}, err
	}
	id1, id2, err := xxtBbsCircleProvider(c, p.Mid, p.JobID, p.KnowledgeID, p.ClassID, p.Enc, utEnc, p.CourseID, p.IsJob)
	if err != nil {
		return xxtBbsPrepared{}, fmt.Errorf("xuexitong: bbs web circle id failed: %w", err)
	}
	infoRaw, err := xxtBbsWebInfoProvider(c, id1, id2, p.CourseID, p.ClassID)
	if err != nil {
		return xxtBbsPrepared{}, fmt.Errorf("xuexitong: bbs web info failed: %w", err)
	}
	topic, err := xxtmobile.ParseWebBbsTopic(infoRaw)
	if err != nil {
		return xxtBbsPrepared{}, fmt.Errorf("xuexitong: bbs web topic parse failed: %w", err)
	}
	topic.CourseID = p.CourseID
	topic.ClassID = p.ClassID
	p.Topic = topic
	p.InfoResp = infoRaw
	p.DetailResp = "utEnc=" + utEnc + ";circle=" + id1 + "/" + id2
	return p, nil
}

func xxtBbsWebCaptcha(sess SessionData, input TaskInput) (RunTaskResult, error) {
	c := xxtClient(sess)
	imgB64, err := xxtChapterCaptchaImgProvider(c)
	if err != nil {
		return RunTaskResult{}, fmt.Errorf("xuexitong: bbs web captcha image failed: %w", err)
	}
	return RunTaskResult{
		Platform: "xuexitong",
		TaskID:   strOf(input.Raw["jobId"]),
		Status:   "captcha",
		Message:  "BBS web triggered character captcha; host OCR then call passChapterCaptcha and retry bbsWebPrepare",
		Raw: map[string]interface{}{
			"submitMode":      "bbsWeb",
			"captchaImage":    imgB64,
			"requiresHostOCR": true,
			"realSubmit":      false,
		},
	}, nil
}

func xxtBbsWebRaw(p xxtBbsPrepared, realSubmit bool, response string) map[string]interface{} {
	raw := xxtBbsRaw(p, realSubmit, response)
	raw["submitMode"] = "bbsWeb"
	raw["requiresHostOCR"] = false
	return raw
}
