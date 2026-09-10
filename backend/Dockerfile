FROM golang:1.22-alpine AS build
WORKDIR /src
COPY go.mod ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -o /out/zkq-cloud .
FROM alpine:3.20
RUN adduser -D app
USER app
WORKDIR /app
COPY --from=build /out/zkq-cloud .
VOLUME /app/data
EXPOSE 8080
ENTRYPOINT ["/app/zkq-cloud"]
