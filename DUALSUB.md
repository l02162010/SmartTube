# SmartTube 雙語字幕 fork：維護手冊

這個 fork 在 SmartTube 上加了「原文＋AI 翻譯」雙語字幕（Claude API），全部在電視上完成，不需要後端。
分支：`feature/dualsub`。上游改動都標了 `MOD: dual subtitles`。

## 一次性的待辦

- [ ] **電視上關掉「無線偵錯」**：設定 → 系統 → 開發人員選項 → 無線偵錯 → 關。電腦斷開連線不等於電視關掉，開著的話同一個 Wi-Fi 的裝置都可能連進來。只在要安裝或更新時才打開。
- [ ] **備份簽章金鑰**：把 `~/.android-keystores/smarttube-dualsub.jks` 和 `keystore.properties`（repo 根目錄，git 已忽略）一起存進密碼管理器或加密備份。弄丟的話，新版本無法覆蓋安裝，只能先移除再重裝，設定和快取都會清掉。

## 本機檔案（都不在 git 裡）

| 檔案 | 用途 |
| --- | --- |
| `.env` | `CLAUDE_API_KEY`、`CLAUDE_MODEL`，建置時寫進 APK。範本見 `.env.example` |
| `keystore.properties` | release 簽章設定（金鑰路徑與密碼） |
| `~/.android-keystores/smarttube-dualsub.jks` | release 簽章金鑰 |
| `local.properties` | Android SDK 路徑 |

## 建置

用 **JDK 11**（上游警告新版 JDK 編出來的 app 會閃退）。`gradlew` 沒有執行權限，用 `sh` 執行。

```sh
export JAVA_HOME=$(find ~/Library/Java/JavaVirtualMachines/zulu11* -maxdepth 3 -type d -name Home | head -1)
sh ./gradlew :smarttubetv:assembleStstableRelease
# 輸出：smarttubetv/build/outputs/apk/ststable/release/
```

單元測試：`sh ./gradlew :common:testStstableDebugUnitTest --tests '*dualsub*'`

## 安裝或更新到 Chromecast

Chromecast with Google TV 是 32 位元系統，裝 `SmartTube_stable_*_armeabi-v7a.apk`。

1. 電視上打開「無線偵錯」。
2. 第一次（或換電腦）要先配對：選「使用配對碼配對裝置」，再執行：
   ```sh
   adb mdns services                 # 找出 _adb-tls-pairing 與 _adb-tls-connect 的 IP:port
   adb pair <IP>:<配對port> <6位數配對碼>
   ```
3. 連線並安裝：
   ```sh
   adb connect <IP>:<連線port>
   adb install -r smarttubetv/build/outputs/apk/ststable/release/SmartTube_stable_*_armeabi-v7a.apk
   adb disconnect
   ```
   `-r` 會保留設定和翻譯快取（前提是用同一把金鑰簽章）。
4. **裝完把無線偵錯關掉。**

## 合併上游新版

```sh
git fetch upstream
git merge upstream/master
git submodule update --init      # 上游常更新 MediaServiceCore
```

衝突只會出現在標了 `MOD: dual subtitles` 的幾行。注意：上游部分檔案是 **CRLF** 換行，編輯時要保留，不然整個檔案都會變成 diff。
合併完先跑單元測試，再建置 release 版，照上面的步驟安裝。

## 換 Claude API key

優先順序：**設定頁填的 key ＞ `.env` 編進 APK 的 key**。

1. **手機掃 QR code（最方便）**：設定 → 字幕 → 「用手機設定 API key（掃 QR code）」。手機要連同一個 Wi-Fi，連結用過一次或 5 分鐘後失效。
2. **設定頁直接輸入**：設定 → 字幕 → 「Claude API key」，用手機上的 Google TV 遙控器 app 鍵盤貼上。
3. **改 `.env` 重新建置**：只有在設定頁從沒填過 key 時才有效。

key 外洩時：先到 console.anthropic.com 撤銷舊 key，再用上面的方式換新的。舊 APK 裡仍有舊 key，不要分享 APK。

## 疑難排解

- **模擬器連不上網（UnknownHostException）**：用 `emulator -avd <名稱> -dns-server 8.8.8.8,1.1.1.1` 啟動。
- **模擬器上的 QR code 手機打不開**：正常，模擬器的位址（10.0.2.x）在你的網路上連不到，請在實機上用。
- **翻譯失敗的提示**：用 `adb logcat | grep DualSubtitleController` 看原因（例如餘額不足、key 被拒絕）。
- **Homebrew 或 GitHub 下載很慢**：這個網路連 GitHub CDN 只有約 1 Mbps，JDK 改從 Azul（cdn.azul.com）下載。

## Android 開發者驗證（keepandroidopen.org）

截至 2026-10：用 ADB 安裝不受影響，強制執行只適用於手機與平板，台灣不在第一波。
若日後擴大到電視，而且想用 ADB 以外的方式安裝，可申請免費的「有限發行帳號」（最多 20 台裝置，不需身分證件）。
參考：<https://developer.android.com/developer-verification/guides/faq>
