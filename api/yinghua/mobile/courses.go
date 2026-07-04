package mobile

import (
	"encoding/json"
	"fmt"
	"strconv"
	"time"
)

// CourseListItem represents one yinghua course from /api/course/list.json.
type CourseListItem struct {
	ID           float64 `json:"id"`
	Name         string  `json:"name"`
	Mode         int     `json:"mode"`
	Progress     float64 `json:"progress"`
	StartDate    string  `json:"startDate"`
	EndDate      string  `json:"endDate"`
	VideoCount   int     `json:"videoCount"`
	VideoLearned int     `json:"videoLearned"`
}

// CourseDetailResp is the parsed result of /api/course/detail.json.
type CourseDetailResp struct {
	Code   int    `json:"_code"`
	Status bool   `json:"status"`
	Msg    string `json:"msg"`
	Result struct {
		Data CourseListItem `json:"data"`
	} `json:"result"`
}

// CourseListResp is the parsed result of /api/course/list.json.
type CourseListResp struct {
	Code   int    `json:"_code"`
	Status bool   `json:"status"`
	Msg    string `json:"msg"`
	Result struct {
		List []CourseListItem `json:"list"`
	} `json:"result"`
}

// ChapterNode is one video node within a chapter.
type ChapterNode struct {
	ID            float64 `json:"id"`
	Name          string  `json:"name"`
	VideoDuration string  `json:"videoDuration"`
	NodeLock      int     `json:"nodeLock"`
	UnlockTime    string  `json:"unlockTime"`
	VideoState    int     `json:"videoState"`
	Duration      string  `json:"duration"`
	Index         string  `json:"index"`
	TabVideo      bool    `json:"tabVideo"`
	TabFile       bool    `json:"tabFile"`
	TabVote       bool    `json:"tabVote"`
	TabExam       bool    `json:"tabExam"`
	TabWork       bool    `json:"tabWork"`
}

// Chapter is a chapter (section) containing nodes.
type Chapter struct {
	ID       float64       `json:"id"`
	Name     string        `json:"name"`
	NodeList []ChapterNode `json:"nodeList"`
}

// NodeVideoMeta is parsed from /api/node/video.json.
type NodeVideoMeta struct {
	StudyID       string `json:"studyId"`
	VideoDuration int    `json:"videoDuration"`
}

// VideoRecordItem carries one entry from /api/record/video.json.
type VideoRecordItem struct {
	ID             string  `json:"id"`
	Name           string  `json:"name"`
	CourseID       string  `json:"courseId"`
	VideoDuration  int     `json:"videoDuration"`
	BID            string  `json:"bid"`
	Duration       int     `json:"duration"`
	Progress       float64 `json:"progress"`
	State          int     `json:"state"`
	ViewCount      int     `json:"viewCount"`
	ViewedDuration int     `json:"viewedDuration"`
	Error          int     `json:"error"`
	ErrorMessage   string  `json:"errorMessage"`
}

func (v *VideoRecordItem) UnmarshalJSON(data []byte) error {
	var raw struct {
		ID             interface{} `json:"id"`
		Name           string      `json:"name"`
		CourseID       interface{} `json:"courseId"`
		VideoDuration  interface{} `json:"videoDuration"`
		BID            string      `json:"bid"`
		Duration       interface{} `json:"duration"`
		Progress       interface{} `json:"progress"`
		State          interface{} `json:"state"`
		ViewCount      interface{} `json:"viewCount"`
		ViewedDuration interface{} `json:"viewedDuration"`
		Error          interface{} `json:"error"`
		ErrorMessage   string      `json:"errorMessage"`
	}
	if err := json.Unmarshal(data, &raw); err != nil {
		return err
	}
	v.ID = jsonScalarString(raw.ID)
	v.Name = raw.Name
	v.CourseID = jsonScalarString(raw.CourseID)
	v.VideoDuration = jsonScalarInt(raw.VideoDuration)
	v.BID = raw.BID
	v.Duration = jsonScalarInt(raw.Duration)
	v.Progress = jsonScalarFloat(raw.Progress)
	v.State = jsonScalarInt(raw.State)
	v.ViewCount = jsonScalarInt(raw.ViewCount)
	v.ViewedDuration = jsonScalarInt(raw.ViewedDuration)
	v.Error = jsonScalarInt(raw.Error)
	v.ErrorMessage = raw.ErrorMessage
	return nil
}

// VideoRecordPage is a page from /api/record/video.json.
type VideoRecordPage struct {
	Items     []VideoRecordItem
	Page      int
	PageCount int
}

// PCVideoRecordItem carries one entry from /user/study_record/video.json.
type PCVideoRecordItem struct {
	ID           string `json:"id"`
	Error        int    `json:"error"`
	ErrorMessage string `json:"errorMessage"`
}

func (v *PCVideoRecordItem) UnmarshalJSON(data []byte) error {
	var raw struct {
		ID           interface{} `json:"id"`
		Error        interface{} `json:"error"`
		ErrorMessage string      `json:"errorMessage"`
	}
	if err := json.Unmarshal(data, &raw); err != nil {
		return err
	}
	v.ID = jsonScalarString(raw.ID)
	v.Error = jsonScalarInt(raw.Error)
	v.ErrorMessage = raw.ErrorMessage
	return nil
}

// PCVideoRecordPage is a page from /user/study_record/video.json.
type PCVideoRecordPage struct {
	Items     []PCVideoRecordItem
	Page      int
	PageCount int
}

// CourseList fetches /api/course/list.json. Returns raw JSON string.
func (c *YingHuaClient) CourseList() (string, error) {
	b, err := c.postForm(c.PreURL+"/api/course/list.json", map[string]string{
		"platform": "Android",
		"version":  "1.4.8",
		"type":     "0",
		"token":    c.Token,
	})
	return string(b), err
}

// CourseDetail fetches /api/course/detail.json for the given courseId.
func (c *YingHuaClient) CourseDetail(courseID string) (string, error) {
	b, err := c.postForm(c.PreURL+"/api/course/detail.json", map[string]string{
		"platform": "Android",
		"version":  "1.4.8",
		"courseId": courseID,
		"token":    c.Token,
	})
	return string(b), err
}

// CourseChapter fetches /api/course/chapter.json (chapter+node list) for a course.
func (c *YingHuaClient) CourseChapter(courseID string) (string, error) {
	b, err := c.postForm(c.PreURL+"/api/course/chapter.json", map[string]string{
		"platform": "Android",
		"version":  "1.4.8",
		"courseId": courseID,
		"token":    c.Token,
	})
	return string(b), err
}

// CourseVideoRecords fetches /api/record/video.json for current study progress.
func (c *YingHuaClient) CourseVideoRecords(courseID string, page int) (string, error) {
	b, err := c.postForm(c.PreURL+"/api/record/video.json", map[string]string{
		"platform": "Android",
		"version":  "1.4.8",
		"courseId": courseID,
		"page":     strconv.Itoa(page),
		"token":    c.Token,
	})
	return string(b), err
}

// CourseVideoRecordsPC fetches the PC-side red/error state record list.
func (c *YingHuaClient) CourseVideoRecordsPC(courseID string, page int) (string, error) {
	b, err := c.get(c.PreURL + "/user/study_record/video.json?courseId=" + courseID + "&_=" + strconv.FormatInt(time.Now().Unix(), 10) + "&page=" + strconv.Itoa(page))
	return string(b), err
}

// VideoStudyState fetches /api/node/video.json for current study state of a node.
func (c *YingHuaClient) VideoStudyState(nodeID string) (string, error) {
	b, err := c.postForm(c.PreURL+"/api/node/video.json", map[string]string{
		"platform": "Android",
		"version":  "1.4.8",
		"nodeId":   nodeID,
		"token":    c.Token,
	})
	return string(b), err
}

// SubmitStudyTime posts /api/node/study.json to record watched time.
func (c *YingHuaClient) SubmitStudyTime(nodeID, studyID string, studyTime int) (string, error) {
	b, err := c.postForm(c.PreURL+"/api/node/study.json", map[string]string{
		"platform":  "Android",
		"version":   "1.4.8",
		"nodeId":    nodeID,
		"token":     c.Token,
		"terminal":  "Android",
		"studyTime": strconv.Itoa(studyTime),
		"studyId":   studyID,
	})
	return string(b), err
}

// KeepAlive posts /api/online.json to keep the login session warm.
// The host drives this on a ~5-minute ticker; on expiry it must re-login.
func (c *YingHuaClient) KeepAlive() (string, error) {
	b, err := c.postForm(c.PreURL+"/api/online.json", map[string]string{
		"platform": "Android",
		"version":  "1.4.8",
		"token":    c.Token,
	})
	return string(b), err
}

// ParseCourseListResp parses /api/course/list.json. Exported for tests.
// Success: status==true AND _code==0.
func ParseCourseListResp(raw string) (*CourseListResp, error) {
	var resp CourseListResp
	if err := json.Unmarshal([]byte(raw), &resp); err != nil {
		return nil, fmt.Errorf("yinghua: course list parse error: %w", err)
	}
	if !resp.Status || resp.Code != 0 {
		return nil, fmt.Errorf("yinghua: course list failed: %s", resp.Msg)
	}
	return &resp, nil
}

// ParseCourseDetailResp parses /api/course/detail.json.
func ParseCourseDetailResp(raw string) (*CourseDetailResp, error) {
	var resp CourseDetailResp
	if err := json.Unmarshal([]byte(raw), &resp); err != nil {
		return nil, fmt.Errorf("yinghua: course detail parse error: %w", err)
	}
	if !resp.Status || resp.Code != 0 {
		return nil, fmt.Errorf("yinghua: course detail failed: %s", resp.Msg)
	}
	return &resp, nil
}

// ParseCourseChapter parses /api/course/chapter.json. Exported for tests.
// Success: status==true AND _code==0.
func ParseCourseChapter(raw string) ([]Chapter, error) {
	var resp struct {
		Code   int    `json:"_code"`
		Status bool   `json:"status"`
		Msg    string `json:"msg"`
		Result struct {
			List []Chapter `json:"list"`
		} `json:"result"`
	}
	if err := json.Unmarshal([]byte(raw), &resp); err != nil {
		return nil, fmt.Errorf("yinghua: chapter parse error: %w", err)
	}
	if !resp.Status || resp.Code != 0 {
		return nil, fmt.Errorf("yinghua: chapter failed: %s", resp.Msg)
	}
	return resp.Result.List, nil
}

// ParseVideoRecordPage parses /api/record/video.json.
func ParseVideoRecordPage(raw string) (VideoRecordPage, error) {
	var resp struct {
		Code   int    `json:"_code"`
		Status bool   `json:"status"`
		Msg    string `json:"msg"`
		Result struct {
			List     []VideoRecordItem `json:"list"`
			PageInfo struct {
				Page      int `json:"page"`
				PageCount int `json:"pageCount"`
			} `json:"pageInfo"`
		} `json:"result"`
	}
	if err := json.Unmarshal([]byte(raw), &resp); err != nil {
		return VideoRecordPage{}, fmt.Errorf("yinghua: video record parse error: %w", err)
	}
	if !resp.Status || resp.Code != 0 {
		return VideoRecordPage{}, fmt.Errorf("yinghua: video record failed: %s", resp.Msg)
	}
	return VideoRecordPage{
		Items:     resp.Result.List,
		Page:      resp.Result.PageInfo.Page,
		PageCount: resp.Result.PageInfo.PageCount,
	}, nil
}

// ParsePCVideoRecordPage parses /user/study_record/video.json.
func ParsePCVideoRecordPage(raw string) (PCVideoRecordPage, error) {
	var resp struct {
		List     []PCVideoRecordItem `json:"list"`
		PageInfo struct {
			Page      int `json:"page"`
			PageCount int `json:"pageCount"`
		} `json:"pageInfo"`
	}
	if err := json.Unmarshal([]byte(raw), &resp); err != nil {
		return PCVideoRecordPage{}, fmt.Errorf("yinghua: pc video record parse error: %w", err)
	}
	return PCVideoRecordPage{
		Items:     resp.List,
		Page:      resp.PageInfo.Page,
		PageCount: resp.PageInfo.PageCount,
	}, nil
}

// ParseNodeVideoMeta parses /api/node/video.json. Exported for tests.
func ParseNodeVideoMeta(raw string) (*NodeVideoMeta, error) {
	var resp struct {
		Status bool   `json:"status"`
		Msg    string `json:"msg"`
		Result struct {
			StudyID       string `json:"studyId"`
			VideoDuration int    `json:"videoDuration"`
		} `json:"result"`
	}
	if err := json.Unmarshal([]byte(raw), &resp); err != nil {
		return nil, fmt.Errorf("yinghua: node video parse error: %w", err)
	}
	if !resp.Status {
		return nil, fmt.Errorf("yinghua: node video failed: %s", resp.Msg)
	}
	return &NodeVideoMeta{
		StudyID:       resp.Result.StudyID,
		VideoDuration: resp.Result.VideoDuration,
	}, nil
}

func jsonScalarString(v interface{}) string {
	switch x := v.(type) {
	case string:
		return x
	case float64:
		return strconv.FormatFloat(x, 'f', 0, 64)
	case int:
		return strconv.Itoa(x)
	case json.Number:
		return x.String()
	default:
		return ""
	}
}

func jsonScalarInt(v interface{}) int {
	switch x := v.(type) {
	case string:
		i, _ := strconv.Atoi(x)
		return i
	case float64:
		return int(x)
	case int:
		return x
	case json.Number:
		i, _ := strconv.Atoi(x.String())
		return i
	default:
		return 0
	}
}

func jsonScalarFloat(v interface{}) float64 {
	switch x := v.(type) {
	case string:
		f, _ := strconv.ParseFloat(x, 64)
		return f
	case float64:
		return x
	case int:
		return float64(x)
	case json.Number:
		f, _ := strconv.ParseFloat(x.String(), 64)
		return f
	default:
		return 0
	}
}

// ParseStudySubmitResult checks whether /api/node/study.json reported success.
// Exported for tests.
func ParseStudySubmitResult(raw string) (*NodeVideoMeta, error) {
	var resp struct {
		Status bool   `json:"status"`
		Msg    string `json:"msg"`
		Result struct {
			Data struct {
				StudyID       interface{} `json:"studyId"`
				VideoDuration interface{} `json:"videoDuration"`
			} `json:"data"`
			StudyID       interface{} `json:"studyId"`
			VideoDuration interface{} `json:"videoDuration"`
		} `json:"result"`
	}
	if err := json.Unmarshal([]byte(raw), &resp); err != nil {
		return nil, fmt.Errorf("yinghua: study submit parse error: %w", err)
	}
	if !resp.Status {
		return nil, fmt.Errorf("yinghua: study submit failed: %s", resp.Msg)
	}
	studyID := jsonScalarString(resp.Result.Data.StudyID)
	if studyID == "" {
		studyID = jsonScalarString(resp.Result.StudyID)
	}
	duration := jsonScalarInt(resp.Result.Data.VideoDuration)
	if duration == 0 {
		duration = jsonScalarInt(resp.Result.VideoDuration)
	}
	return &NodeVideoMeta{StudyID: studyID, VideoDuration: duration}, nil
}

// ParseKeepAliveResult interprets /api/online.json.
// alive=true: session still valid. expired=true: server reported the session is
// no longer authenticated (status=false) — the host should re-login. A transient
// gateway/parse error returns (false,false) so the host may simply retry.
func ParseKeepAliveResult(raw string) (alive, expired bool) {
	var resp struct {
		Status bool `json:"status"`
		Code   int  `json:"_code"`
	}
	if err := json.Unmarshal([]byte(raw), &resp); err != nil {
		return false, false
	}
	if resp.Status {
		return true, false
	}
	return false, true
}
