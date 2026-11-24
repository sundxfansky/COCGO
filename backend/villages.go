package main

import (
	"database/sql"
	"encoding/json"
	"io"
	"net/http"
	"strconv"
)

type villageSlot struct {
	Region  string `json:"region"`
	Account int    `json:"account"`
}

type villageSnapshot struct {
	Tag         string           `json:"tag,omitempty"`
	Timestamp   int64            `json:"timestamp,omitempty"`
	TownHall    int              `json:"townHall,omitempty"`
	BuilderHall int              `json:"builderHall,omitempty"`
	Items       []villageItem    `json:"items"`
	Boosts      map[string]int64 `json:"boosts,omitempty"`
}

type villageItem struct {
	Group    string `json:"group"`
	ID       int32  `json:"id"`
	Count    int    `json:"count"`
	Level    *int   `json:"level,omitempty"`
	Seconds  int64  `json:"seconds,omitempty"`
	Weapon   int    `json:"weapon,omitempty"`
	GearUp   int    `json:"gearUp,omitempty"`
	Cooldown int64  `json:"cooldown,omitempty"`
	Parent   int32  `json:"parent,omitempty"`
}

func (s *server) listVillages(w http.ResponseWriter, r *http.Request) {
	owner, device, ok := s.authorizeDevice(w, r)
	if !ok {
		return
	}
	rows, err := s.db.QueryContext(r.Context(), `
		SELECT region, account FROM device_villages
		WHERE user_id=? AND device_id=? ORDER BY account,region`, owner, device)
	if err != nil {
		jsonOut(w, http.StatusInternalServerError, map[string]string{"error": "无法读取村庄列表"})
		return
	}
	defer rows.Close()
	slots := []villageSlot{}
	for rows.Next() {
		var slot villageSlot
		if err := rows.Scan(&slot.Region, &slot.Account); err != nil {
			jsonOut(w, http.StatusInternalServerError, map[string]string{"error": "无法读取村庄列表"})
			return
		}
		slots = append(slots, slot)
	}
	if rows.Err() != nil {
		jsonOut(w, http.StatusInternalServerError, map[string]string{"error": "无法读取村庄列表"})
		return
	}
	jsonOut(w, http.StatusOK, map[string]any{"exports": slots})
}

func (s *server) deviceVillage(w http.ResponseWriter, r *http.Request) {
	owner, device, ok := s.authorizeDevice(w, r)
	if !ok {
		return
	}
	region, key := r.PathValue("region"), r.PathValue("account")
	account, err := strconv.Atoi(key)
	if (region != "cn" && region != "global") || err != nil || account < 1 || account > 10000 || key != strconv.Itoa(account) {
		jsonOut(w, http.StatusNotFound, map[string]string{"error": "游戏账号或区服无效"})
		return
	}
	if r.Method == http.MethodGet {
		var payload []byte
		err := s.db.QueryRowContext(r.Context(), `
			SELECT payload FROM device_villages WHERE user_id=? AND device_id=? AND region=? AND account=?`,
			owner, device, region, account).Scan(&payload)
		switch err {
		case nil:
			jsonOut(w, http.StatusOK, json.RawMessage(payload))
		case sql.ErrNoRows:
			jsonOut(w, http.StatusNotFound, map[string]string{"error": "这个账号还没有发布村庄数据"})
		default:
			jsonOut(w, http.StatusInternalServerError, map[string]string{"error": "无法读取村庄数据"})
		}
		return
	}

	// Persist the display format only. Village uploads are independent of configuration revisions.
	var snapshot villageSnapshot
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&snapshot); err != nil || decoder.Decode(&struct{}{}) != io.EOF || !snapshot.valid() {
		jsonOut(w, http.StatusBadRequest, map[string]string{"error": "村庄数据格式无效"})
		return
	}
	payload, err := json.Marshal(snapshot)
	if err == nil {
		_, err = s.db.ExecContext(r.Context(), `
			INSERT INTO device_villages(user_id, device_id, region, account, payload) VALUES(?,?,?,?,?)
			ON CONFLICT(user_id, device_id, region, account) DO UPDATE SET payload=excluded.payload`,
			owner, device, region, account, string(payload))
	}
	if err != nil {
		jsonOut(w, http.StatusInternalServerError, map[string]string{"error": "无法保存村庄数据"})
		return
	}
	jsonOut(w, http.StatusOK, map[string]bool{"saved": true})
}

func (snapshot villageSnapshot) valid() bool {
	if len(snapshot.Items) == 0 || len(snapshot.Items) > 4000 ||
		(snapshot.TownHall == 0 && snapshot.BuilderHall == 0) ||
		snapshot.TownHall < 0 || snapshot.TownHall > 100 || snapshot.BuilderHall < 0 || snapshot.BuilderHall > 100 ||
		(snapshot.Timestamp != 0 && (snapshot.Timestamp < 1420070400 || snapshot.Timestamp > 4102444800)) ||
		(snapshot.Tag != "" && !validVillageTag(snapshot.Tag)) || len(snapshot.Boosts) > 7 {
		return false
	}
	for _, item := range snapshot.Items {
		if item.ID <= 0 || item.Count < 1 || item.Count > 10000 || !validVillageGroup(item.Group) ||
			(item.Level != nil && (*item.Level < 0 || *item.Level > 1000)) ||
			item.Seconds < 0 || item.Seconds > 316224000 || item.Cooldown < 0 || item.Cooldown > 316224000 ||
			item.Weapon < 0 || item.Weapon > 100 || item.GearUp < 0 || item.GearUp > 100 || item.Parent < 0 {
			return false
		}
	}
	for key, seconds := range snapshot.Boosts {
		switch key {
		case "builder_boost", "builder_consumable", "lab_boost", "lab_consumable", "pet_boost", "clocktower_boost", "clocktower_cooldown":
			if seconds < 1 || seconds > 316224000 {
				return false
			}
		default:
			return false
		}
	}
	return true
}

func validVillageGroup(group string) bool {
	switch group {
	case "buildings", "traps", "units", "siege_machines", "heroes", "spells", "pets", "equipment",
		"helpers", "guardians", "decos", "obstacles", "house_parts", "skins", "sceneries",
		"buildings2", "traps2", "units2", "heroes2", "decos2", "obstacles2", "skins2", "sceneries2":
		return true
	}
	return false
}

func validVillageTag(tag string) bool {
	if len(tag) < 3 || len(tag) > 17 || tag[0] != '#' {
		return false
	}
	for _, char := range tag[1:] {
		if !(char >= 'A' && char <= 'Z' || char >= 'a' && char <= 'z' || char >= '0' && char <= '9') {
			return false
		}
	}
	return true
}
