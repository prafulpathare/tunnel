package main

import (
	"context"
	"encoding/base64"
	"fmt"
	"io"
	"log"
	"net/http"
	"sync"
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

var upgrader = websocket.Upgrader{CheckOrigin: func(r *http.Request) bool { return true }}

type clientConn struct {
	ws   *websocket.Conn
	send chan Msg
}

var clients sync.Map
var pending sync.Map

func wsHandler(w http.ResponseWriter, r *http.Request) {
	token := r.URL.Query().Get("token")
	if token == "" {
		http.Error(w, "missing token", http.StatusBadRequest)
		return
	}
	ws, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Println("upgrade:", err)
		return
	}

	cc := &clientConn{ws: ws, send: make(chan Msg, 32)}
	clients.Store(token, cc)
	log.Println("client connected:", token)

	defer func() {
		clients.Delete(token)
		close(cc.send)
		ws.Close()
		log.Println("client disconnected:", token)
	}()

	go func() {
		for {
			var m Msg
			if err := ws.ReadJSON(&m); err != nil {
				return
			}
			if m.Type == "res" {
				if ch, ok := pending.Load(m.ID); ok {
					ch.(chan Msg) <- m
				}
			}
		}
	}()

	for m := range cc.send {
		ws.WriteJSON(m)
	}
}

func proxyHandler(w http.ResponseWriter, r *http.Request) {
	path := r.URL.Path
	if len(path) < 2 {
		http.Error(w, "bad path", 400)
		return
	}

	// split /token/...
	i := 1
	for ; i < len(path); i++ {
		if path[i] == '/' {
			break
		}
	}
	token := path[1:i]
	rest := "/"
	if i < len(path) {
		rest = path[i:]
	}

	v, ok := clients.Load(token)
	if !ok {
		http.Error(w, "client offline", 502)
		return
	}
	cc := v.(*clientConn)

	body, _ := io.ReadAll(r.Body)
	id := fmt.Sprintf("%d", time.Now().UnixNano())

	msg := Msg{
		Type:    "req",
		ID:      id,
		Method:  r.Method,
		Path:    rest,
		Headers: r.Header,
		BodyB64: base64.StdEncoding.EncodeToString(body),
	}

	respCh := make(chan Msg, 1)
	pending.Store(id, respCh)
	defer pending.Delete(id)

	cc.send <- msg

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	select {
	case res := <-respCh:
		if res.Error != "" {
			http.Error(w, res.Error, 502)
			return
		}
		for k, vv := range res.Headers {
			for _, v := range vv {
				w.Header().Add(k, v)
			}
		}
		if res.Status == 0 {
			res.Status = 200
		}
		w.WriteHeader(res.Status)
		if res.BodyB64 != "" {
			b, _ := base64.StdEncoding.DecodeString(res.BodyB64)
			w.Write(b)
		}
	case <-ctx.Done():
		http.Error(w, "timeout", 504)
	}
}

func main() {
	http.HandleFunc("/ws", wsHandler)
	http.HandleFunc("/", proxyHandler)
	log.Println("listening on :8080")
	log.Fatal(http.ListenAndServe(":8080", nil))
}
