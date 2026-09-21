package main

import (
	"crypto/tls"
	"fmt"
	"net"
	"net/mail"
	"net/smtp"
	"strconv"
	"strings"
	"time"
)

type smtpSettings struct {
	Host     string `json:"host"`
	Port     string `json:"port"`
	User     string `json:"user"`
	Password string `json:"password"`
	From     string `json:"from"`
	Security string `json:"security"`
}

func validEmail(value string) bool {
	address, err := mail.ParseAddress(value)
	return err == nil && address.Address == value && !strings.ContainsAny(value, "\r\n")
}

func (settings smtpSettings) configured() bool {
	return settings.Host != "" && settings.User != "" && settings.Password != "" && settings.validate() == nil
}

func (settings smtpSettings) validate() error {
	port, err := strconv.Atoi(settings.Port)
	if err != nil || port < 1 || port > 65535 {
		return fmt.Errorf("SMTP 端口必须为 1–65535")
	}
	if settings.Security != "tls" && settings.Security != "starttls" {
		return fmt.Errorf("请选择 STARTTLS 或 TLS 加密")
	}
	if strings.ContainsAny(settings.Host, " /\r\n\t") || strings.ContainsAny(settings.User, "\r\n") {
		return fmt.Errorf("SMTP 主机或用户名格式错误")
	}
	if settings.From != "" && !validEmail(settings.From) {
		return fmt.Errorf("发件人必须是有效邮箱")
	}
	if settings.Host != "" && settings.From == "" && !validEmail(settings.User) {
		return fmt.Errorf("用户名不是邮箱时必须填写发件人邮箱")
	}
	return nil
}

func sendMail(settings smtpSettings, recipient, subject, body string) error {
	if !settings.configured() || !validEmail(recipient) {
		return fmt.Errorf("invalid mail configuration")
	}
	address := net.JoinHostPort(settings.Host, settings.Port)
	tlsConfig := &tls.Config{ServerName: settings.Host, MinVersion: tls.VersionTLS12}
	connection, err := net.DialTimeout("tcp", address, 10*time.Second)
	if err != nil {
		return err
	}
	defer connection.Close()
	if err := connection.SetDeadline(time.Now().Add(20 * time.Second)); err != nil {
		return err
	}
	// Both supported modes require verified TLS before credentials leave the server.
	if settings.Security == "tls" {
		connection = tls.Client(connection, tlsConfig)
	}
	client, err := smtp.NewClient(connection, settings.Host)
	if err != nil {
		return err
	}
	defer client.Close()
	if settings.Security == "starttls" {
		if err := client.StartTLS(tlsConfig); err != nil {
			return err
		}
	}
	if err := client.Auth(smtp.PlainAuth("", settings.User, settings.Password, settings.Host)); err != nil {
		return err
	}
	from := settings.From
	if from == "" {
		from = settings.User
	}
	if err := client.Mail(from); err != nil {
		return err
	}
	if err := client.Rcpt(recipient); err != nil {
		return err
	}
	writer, err := client.Data()
	if err != nil {
		return err
	}
	message := "From: " + from + "\r\nTo: " + recipient + "\r\nSubject: " + subject + "\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n" + body + "\r\n"
	if _, err := writer.Write([]byte(message)); err != nil {
		return err
	}
	if err := writer.Close(); err != nil {
		return err
	}
	return client.Quit()
}
