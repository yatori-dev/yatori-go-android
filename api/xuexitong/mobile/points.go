package mobile

import (
	"encoding/json"
	"fmt"
	"io/ioutil"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/PuerkitoBio/goquery"
)

// KnowledgePoint is one task point discovered inside a knowledge node's cards.
// It mirrors the console's PointDto discovery (module + ids), carrying just enough
// to schedule the point; the per-type submit path re-fetches full card metadata.
type KnowledgePoint struct {
	Module    string // iframe module attr: insertvideo/work/insertdoc/document/insertbook/insertreadV2/hyperlink/insertlive/insertbbs/insertaudio
	ObjectID  string
	JobID     string
	WorkID    string
	SchoolID  string
	Title     string
	CardIndex int
}

// FetchKnowledgeCardsApi pulls a knowledge node's task-point cards (gas/knowledge).
//
// Mirrors console FetchChapterCords: the response's data[0].card.data[].description
// holds card HTML with <iframe module=... data="{...}"> task points. This is the
// authoritative task-point source — the chapter tree's attachment field is not.
func (c *XxtClient) FetchKnowledgeCardsApi(nodeID, courseID int, retry int, lastErr error) (string, error) {
	if retry < 0 {
		return "", lastErr
	}
	values := url.Values{}
	values.Set("id", strconv.Itoa(nodeID))
	values.Set("courseid", strconv.Itoa(courseID))
	values.Set("fields", "id,parentnodeid,indexorder,label,layer,name,begintime,createtime,lastmodifytime,status,jobUnfinishedCount,clickcount,openlock,card.fields(id,knowledgeid,title,knowledgeTitile,description,cardorder).contentcard(all)")
	values.Set("view", "json")
	values.Set("token", "4faa8662c59590c6f43ae9fe5b002b42")
	values.Set("_time", strconv.FormatInt(time.Now().UnixNano()/1000000, 10))

	req, err := http.NewRequest("GET", "https://mooc1-api.chaoxing.com/gas/knowledge?"+values.Encode(), nil)
	if err != nil {
		return c.FetchKnowledgeCardsApi(nodeID, courseID, retry-1, err)
	}
	addCookies(req, c)
	req.Header.Add("User-Agent", mobileUA())
	req.Header.Add("Accept", "*/*")
	req.Header.Add("Accept-Language", "zh_CN")
	req.Header.Add("Host", "mooc1-api.chaoxing.com")
	req.Header.Add("Connection", "Keep-Alive")

	resp, err := (&http.Client{Transport: httpTransport(c)}).Do(req)
	if err != nil {
		return c.FetchKnowledgeCardsApi(nodeID, courseID, retry-1, err)
	}
	defer resp.Body.Close()
	mergeCookies(c, resp.Cookies())
	body, err := ioutil.ReadAll(resp.Body)
	if err != nil {
		return c.FetchKnowledgeCardsApi(nodeID, courseID, retry-1, err)
	}
	raw := string(body)
	if strings.Contains(raw, "请输入验证码") || strings.Contains(raw, "请输入图片中的验证码") {
		return "", fmt.Errorf("xuexitong: 拉取任务点卡片触发验证码")
	}
	if resp.StatusCode != http.StatusOK {
		return c.FetchKnowledgeCardsApi(nodeID, courseID, retry-1, fmt.Errorf("拉取任务点卡片失败 http %d", resp.StatusCode))
	}
	return raw, nil
}

type knowledgeCard struct {
	Description string `json:"description"`
	CardOrder   int    `json:"cardorder"`
	KnowledgeID int    `json:"knowledgeid"`
}

type knowledgeCardsResponse struct {
	Data []struct {
		Card struct {
			Data []knowledgeCard `json:"data"`
		} `json:"card"`
	} `json:"data"`
}

var xxtIframeWhitespace = regexp.MustCompile(`\s+`)

// ParseKnowledgePoints enumerates every task point in a gas/knowledge cards response.
// It walks each card's HTML, reads the <iframe module=... data="{...}"> task points,
// and returns them in card/document order. A container node with no points returns
// an empty slice and no error; only malformed/non-JSON input returns an error.
func ParseKnowledgePoints(cordsJSON string) ([]KnowledgePoint, error) {
	trimmed := strings.TrimSpace(cordsJSON)
	if trimmed == "" || (!strings.HasPrefix(trimmed, "{") && !strings.HasPrefix(trimmed, "[")) {
		return nil, fmt.Errorf("xuexitong: 任务点卡片响应不是 JSON")
	}
	var resp knowledgeCardsResponse
	if err := json.Unmarshal([]byte(trimmed), &resp); err != nil {
		return nil, fmt.Errorf("xuexitong: 任务点卡片解析失败: %w", err)
	}
	if len(resp.Data) == 0 {
		return nil, nil
	}
	cards := resp.Data[0].Card.Data
	points := make([]KnowledgePoint, 0)
	for cardIndex, card := range cards {
		if strings.TrimSpace(card.Description) == "" {
			continue
		}
		for _, f := range parseCardIframes(card.Description) {
			if f.module == "" || !f.hasData {
				continue
			}
			p := KnowledgePoint{
				Module:    f.module,
				CardIndex: cardIndex,
				ObjectID:  iframeStr(f.data, "objectid", "objectId"),
				JobID:     iframeStr(f.data, "jobid", "_jobid"),
				WorkID:    iframeStr(f.data, "workid"),
				SchoolID:  iframeStr(f.data, "schoolid"),
				Title:     iframeUnescape(iframeStr(f.data, "name", "title")),
			}
			if p.ObjectID == "" && p.JobID == "" && p.WorkID == "" {
				continue
			}
			points = append(points, p)
		}
	}
	return points, nil
}

type cardIframe struct {
	module  string
	data    map[string]interface{}
	hasData bool
}

// parseCardIframes extracts <iframe> task points from a card's HTML. The iframe's
// `module` attribute is the point type; the `data` attribute is an (HTML-escaped)
// JSON blob with objectid/jobid/etc. Mirrors console parseIframeData.
func parseCardIframes(html string) []cardIframe {
	doc, err := goquery.NewDocumentFromReader(strings.NewReader(html))
	if err != nil {
		return nil
	}
	out := make([]cardIframe, 0)
	doc.Find("iframe").Each(func(_ int, s *goquery.Selection) {
		f := cardIframe{}
		if module, ok := s.Attr("module"); ok {
			f.module = strings.TrimSpace(module)
		}
		if data, ok := s.Attr("data"); ok && strings.TrimSpace(data) != "" {
			cleaned := strings.ReplaceAll(data, "&quot;", "\"")
			cleaned = xxtIframeWhitespace.ReplaceAllString(cleaned, "")
			var m map[string]interface{}
			if err := json.Unmarshal([]byte(cleaned), &m); err == nil {
				f.data = m
				f.hasData = true
			}
		}
		out = append(out, f)
	})
	return out
}

// iframeStr reads a string from iframe data, trying multiple keys, tolerating
// numbers (chaoxing sometimes sends jobid/workid as int).
func iframeStr(m map[string]interface{}, keys ...string) string {
	for _, k := range keys {
		switch v := m[k].(type) {
		case string:
			if v != "" {
				return v
			}
		case float64:
			return strconv.FormatInt(int64(v), 10)
		}
	}
	return ""
}

// iframeUnescape best-effort URL-decodes a point title (names come URL-encoded).
func iframeUnescape(s string) string {
	if s == "" {
		return s
	}
	if dec, err := url.QueryUnescape(s); err == nil {
		return dec
	}
	return s
}

// NodePointStatus is a knowledge node's task-point completion (from myjobsnodesmap).
type NodePointStatus struct {
	Total    int
	Finished int
}

// IsFinished reports whether every task point in the node is done. It also treats a
// zero-point container node (Total==Finished==0) as finished, mirroring the console.
func (s NodePointStatus) IsFinished() bool { return s.Total == s.Finished }

// FetchChapterPointStatusApi posts to job/myjobsnodesmap for per-node task-point
// completion. Mirrors console FetchChapterPointStatus; one call covers every node id,
// letting the caller skip already-finished nodes before fetching their cards.
func (c *XxtClient) FetchChapterPointStatusApi(nodes []int, clazzID, userID, cpi, courseID int, retry int, lastErr error) (string, error) {
	if retry < 0 {
		return "", lastErr
	}
	ids := make([]string, len(nodes))
	for i, n := range nodes {
		ids[i] = strconv.Itoa(n)
	}
	form := url.Values{
		"view":     {"json"},
		"nodes":    {strings.Join(ids, ",")},
		"clazzid":  {strconv.Itoa(clazzID)},
		"time":     {strconv.FormatInt(time.Now().UnixNano()/1000000, 10)},
		"userid":   {strconv.Itoa(userID)},
		"cpi":      {strconv.Itoa(cpi)},
		"courseid": {strconv.Itoa(courseID)},
	}
	req, err := http.NewRequest("POST", "https://mooc1-api.chaoxing.com/job/myjobsnodesmap", strings.NewReader(form.Encode()))
	if err != nil {
		return c.FetchChapterPointStatusApi(nodes, clazzID, userID, cpi, courseID, retry-1, err)
	}
	addCookies(req, c)
	req.Header.Add("User-Agent", mobileUA())
	req.Header.Add("Accept", "*/*")
	req.Header.Add("Host", "mooc1-api.chaoxing.com")
	req.Header.Add("Connection", "keep-alive")
	req.Header.Add("Content-Type", "application/x-www-form-urlencoded")

	resp, err := (&http.Client{Transport: httpTransport(c)}).Do(req)
	if err != nil {
		return c.FetchChapterPointStatusApi(nodes, clazzID, userID, cpi, courseID, retry-1, err)
	}
	defer resp.Body.Close()
	mergeCookies(c, resp.Cookies())
	body, err := ioutil.ReadAll(resp.Body)
	if err != nil {
		return c.FetchChapterPointStatusApi(nodes, clazzID, userID, cpi, courseID, retry-1, err)
	}
	if resp.StatusCode != http.StatusOK {
		return c.FetchChapterPointStatusApi(nodes, clazzID, userID, cpi, courseID, retry-1, fmt.Errorf("拉取节点完成状态失败 http %d", resp.StatusCode))
	}
	return string(body), nil
}

// ParseNodePointStatus decodes a myjobsnodesmap response — a JSON object keyed by node
// id: {"<id>":{"finishcount":..,"totalcount":..,"unfinishcount":..},...}. When the server
// reports totalcount=0 but unfinishcount>0 (in-progress node), unfinishcount is the total,
// matching console updatePointStatus.
func ParseNodePointStatus(raw string) (map[int]NodePointStatus, error) {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" || !strings.HasPrefix(trimmed, "{") {
		return nil, fmt.Errorf("xuexitong: 节点完成状态响应不是 JSON")
	}
	var m map[string]struct {
		FinishCount   int `json:"finishcount"`
		TotalCount    int `json:"totalcount"`
		UnFinishCount int `json:"unfinishcount"`
	}
	if err := json.Unmarshal([]byte(trimmed), &m); err != nil {
		return nil, fmt.Errorf("xuexitong: 节点完成状态解析失败: %w", err)
	}
	out := make(map[int]NodePointStatus, len(m))
	for k, v := range m {
		id, err := strconv.Atoi(k)
		if err != nil {
			continue
		}
		total := v.TotalCount
		if v.UnFinishCount != 0 && v.TotalCount == 0 {
			total = v.UnFinishCount
		}
		out[id] = NodePointStatus{Total: total, Finished: v.FinishCount}
	}
	return out, nil
}
