package main

import (
	"log"
	"net/http"

	"github.com/acc1111/aircraft-war-hitsz/backend/internal/app"
)

func main() {
	// 小项目直接使用固定数据库文件，默认放在 backend 目录当前工作路径下。
	server, err := app.NewServer(app.Config{DBPath: defaultDBPath()})
	if err != nil {
		log.Fatal(err)
	}
	defer server.Close()
	log.Fatal(http.ListenAndServe(":8080", server.Handler()))
}

func defaultDBPath() string {
	return "aircraft-war.sqlite"
}
