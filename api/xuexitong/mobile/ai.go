package mobile

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"regexp"
	"strings"
)

// XueXiTongAIAggregation runs the built-in Coze bot for a course and returns the
// raw AI answer text (the host supplies the question prompt as content).
func (c *XxtClient) XueXiTongAIAggregation(classId, courseId, cpi, content string) (string, error) {
	informHTML, err := c.aiInformApi(classId, courseId, cpi)
	if err != nil {
		return "", err
	}
	doc := parseHTMLValues(informHTML)
	studentName := ""
	if m := regexp.MustCompile(`"studentName"\s*:\s*"([^"]+)"`).FindStringSubmatch(informHTML); len(m) > 1 {
		studentName = m[1]
	}
	return c.aiAnswerApi(
		doc["cozeEnc"], doc["userId"], doc["courseId"], doc["clazzId"], doc["conversationId"],
		doc["courseName"], studentName, doc["personId"], content,
	)
}

// parseHTMLValues extracts id->value for hidden inputs in the inform HTML.
func parseHTMLValues(html string) map[string]string {
	out := map[string]string{}
	re := regexp.MustCompile(`id="([^"]+)"[^>]*value="([^"]*)"`)
	for _, m := range re.FindAllStringSubmatch(html, -1) {
		if _, ok := out[m[1]]; !ok {
			out[m[1]] = m[2]
		}
	}
	// also support value-before-id ordering
	re2 := regexp.MustCompile(`value="([^"]*)"[^>]*id="([^"]+)"`)
	for _, m := range re2.FindAllStringSubmatch(html, -1) {
		if _, ok := out[m[2]]; !ok {
			out[m[2]] = m[1]
		}
	}
	return out
}

func (c *XxtClient) aiInformApi(clazzId, courseId, cpi string) (string, error) {
	u := "https://stat2-ans.chaoxing.com/bot/index?fromWorkbench=true&upload=true&clazzid=" + clazzId +
		"&showToolbox=false&bgColorNone=true&app_id=1192651262850&courseid=" + courseId + "&cpi=" + cpi +
		"&bot_id=7438777570621653018&ut=s"
	req, err := http.NewRequest("GET", u, nil)
	if err != nil {
		return "", err
	}
	addPCBrowserHeaders(req, "", "")
	req.Header.Add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
	req.Header.Add("Cache-Control", "max-age=0")
	req.Header.Add("Upgrade-Insecure-Requests", "1")
	req.Header.Add("Sec-Fetch-Site", "none")
	req.Header.Add("Sec-Fetch-Mode", "navigate")
	req.Header.Add("Sec-Fetch-User", "?1")
	req.Header.Add("Sec-Fetch-Dest", "document")
	addCookies(req, c)
	res, err := (&http.Client{Transport: httpTransport(c)}).Do(req)
	if err != nil {
		return "", err
	}
	defer res.Body.Close()
	body, err := io.ReadAll(res.Body)
	return string(body), err
}

func (c *XxtClient) aiAnswerApi(cozeEnc, userId, courseId, classId, conversationId, courseName, studentName, personId, content string) (string, error) {
	u := "https://stat2-ans.chaoxing.com/stat2/bot/talk-v1?cozeEnc=" + cozeEnc + "&botId=7438777570621653018&userId=" + userId +
		"&appId=1192651262850&courseid=" + courseId + "&clazzid=" + classId + "&ut=s"
	enc, _ := json.Marshal(content) // safely embed content as a JSON string value
	contentJSON := string(enc)
	if len(contentJSON) >= 2 {
		contentJSON = contentJSON[1 : len(contentJSON)-1] // strip surrounding quotes
	}
	body := `[{"role":"user","content":"` + contentJSON + `","baseData":{"conversationId":"` + conversationId +
		`","userId":"` + userId + `","appId":"1192651262850","botId":"7438777570621653018","custom_variables":{"courseName":"` +
		courseName + `","studentName":"` + studentName + `","weakKnowledgePoint":"{}"},"shortcut_command":{},"sourceInfo":"","sdkFlag":"false","courseid":"` +
		courseId + `","clazzid":"` + classId + `","personid":"` + personId + `"}}]`

	req, err := http.NewRequest("POST", u, strings.NewReader(body))
	if err != nil {
		return "", err
	}
	addPCBrowserHeaders(req, "https://stat2-ans.chaoxing.com", "https://stat2-ans.chaoxing.com/")
	req.Header.Add("Sec-Fetch-Site", "same-origin")
	req.Header.Add("Sec-Fetch-Mode", "cors")
	req.Header.Add("Sec-Fetch-Dest", "empty")
	req.Header.Add("Content-Type", "application/json")
	req.Header.Add("Accept", "*/*")
	addCookies(req, c)
	client := &http.Client{Transport: httpTransport(c)} // no timeout: streamed response
	resp, err := client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()

	reader := bufio.NewReader(resp.Body)
	var final string
	for {
		line, rerr := reader.ReadString('\n')
		if line != "" {
			for _, p := range strings.Split(strings.TrimSpace(line), "$_$") {
				p = strings.TrimSpace(p)
				if p == "" || p == "server-heartbeat" || strings.HasPrefix(p, "server-current-chatid") {
					continue
				}
				var chunk struct {
					Type    string `json:"type"`
					Content string `json:"content"`
				}
				if json.Unmarshal([]byte(p), &chunk) == nil && chunk.Type == "coreAnswer" {
					final += chunk.Content
				}
			}
		}
		if rerr == io.EOF {
			break
		}
		if rerr != nil {
			return "", fmt.Errorf("xuexitong AI: read error: %w", rerr)
		}
	}
	final = strings.ReplaceAll(final, "&quot;", `"`)
	final = strings.ReplaceAll(final, "&nbsp;", ` `)
	final = strings.ReplaceAll(final, "&amp;", `&`)
	final = strings.ReplaceAll(final, "&lt;", `<`)
	final = strings.ReplaceAll(final, "&gt;", `>`)
	return final, nil
}
