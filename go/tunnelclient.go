package main

import (
	"encoding/base64"
	"flag"
	"io"
	"log"
	"net/http"
	"net/url"
	"time"

	"github.com/gorilla/websocket"
)

type Msg struct {
	Type    string              `json:"type"`
	ID      string              `json:"id"`
	Method  string              `json:"method,omitempty"`
	Path    string              `json:"path,omitempty"`
	Headers map[string][]string `json:"headers,omitempty"`
	BodyB64 string              `json:"body_b64,omitempty"`
	Status  int                 `json:"status,omitempty"`
	Error   string              `json:"error,omitempty"`
}

func main() {
	server := flag.String("server", "ws://server:8080/ws", "server ws endpoint")
	token := flag.String("token", "demo", "tunnel token")
	localTarget := flag.String("target", "http://127.0.0.1:3000", "local webapp target")
	flag.Parse()

	u := *server + "?token=" + url.QueryEscape(*token)
	for {
		err := runClient(u, *localTarget)
		log.Println("client disconnected:", err)
		time.Sleep(3 * time.Second)
	}
}

func runClient(wsURL string, target string) error {
	log.Println("connecting to", wsURL)
	ws, _, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err != nil {
		return err
	}
	defer ws.Close()

	for {
		var m Msg
		if err := ws.ReadJSON(&m); err != nil {
			return err
		}
		switch m.Type {
		case "req":
			go func(req Msg) {
				respMsg := handleRequest(req, target)
				if err := ws.WriteJSON(respMsg); err != nil {
					log.Println("writejson error:", err)
				}
			}(m)
		default:
			log.Println("unknown msg type:", m.Type)
		}
	}
}

func handleRequest(m Msg, target string) Msg {
	out := Msg{Type: "res", ID: m.ID}
	body := []byte{}
	if m.BodyB64 != "" {
		b, err := base64.StdEncoding.DecodeString(m.BodyB64)
		if err == nil {
			body = b
		}
	}
	reqURL := target + m.Path
	req, err := http.NewRequest(m.Method, reqURL, bytesReader(body))
	if err != nil {
		out.Error = err.Error()
		return out
	}
	for k, vv := range m.Headers {
		for _, v := range vv {
			req.Header.Add(k, v)
		}
	}
	client := http.Client{Timeout: 15 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		out.Error = err.Error()
		return out
	}
	defer resp.Body.Close()
	out.Status = resp.StatusCode
	out.Headers = resp.Header
	respBody, _ := io.ReadAll(resp.Body)
	out.BodyB64 = base64.StdEncoding.EncodeToString(respBody)
	return out
}

// helper: tiny bytes reader to avoid extra imports
type bytesReaderType struct{ b []byte; i int }
func bytesReader(b []byte) *bytesReaderType { return &bytesReaderType{b: b} }
func (r *bytesReaderType) Read(p []byte) (int, error) {
	if r.i >= len(r.b) { return 0, io.EOF }
	n := copy(p, r.b[r.i:])
	r.i += n
	return n, nil
}
