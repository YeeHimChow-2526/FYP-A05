# hk_poi_master.json — 資料來源（Step 3.1）

產生：`py tools/poi/build_poi_master.py --raw ../poi_raw`（Python 3.11+，只用標準庫；TLS 必驗證）。
輸出：`android/app/src/main/assets/hk_poi_master.json`（built 2026-10-26，153,534 bytes ≤ 153,600）。
下載日期：2026-10-26（香港時間）。全部座標 WGS84。

| # | 資料 | 檔名（--raw 內） | URL | sha256 | 記錄數 | 取用 |
|---|---|---|---|---|---|---|
| A1 | 地政總署 地名（CSDI WFS，csdi:PLACE_NAME） | `csdi_PLACE_NAME.geojson` | https://portal.csdi.gov.hk/server/services/common/landsd_rcd_1648571595120_89752/MapServer/WFSServer?service=wfs&version=2.0.0&request=GetFeature&typeNames=csdi:PLACE_NAME&outputFormat=geojson | `aa05f54b19feaa3e9bc9b7ead0813db1b3c8e262ae4db218fcbc29100e7d909a` | 2,826（Official 2,706；Alias 120） | 只取 NAME_STATUS=Official |
| A2 | 地政總署 地名位置（csdi:GEO_PLACE_NAME） | `csdi_GEO_PLACE_NAME.geojson` | 同上，`typeNames=csdi:GEO_PLACE_NAME` | `8591a1b79940eb54d78c9c388e28097b8a82e1bf19d8b9854ef1719bbae4727c` | 2,706 | 以 GEO_NAME_ID join；只取 PLACE_TYPE ∈ {Area, Town} → 314 個 t=A |
| B | 地政總署 iGeoCom（GeoJSON zip，內含 `iGeoCOM_POI.geojson`） | `iGeoCom_GEOJSON.zip` | https://open.hkmapservice.gov.hk/OpenData/directDownload?productName=iGeoCom&sheetName=iGeoCom&productFormat=GEOJSON | `fb76c35b5beb8c25191dc1e40d7e15ed6f2ba04136ad4f8089a3463d3ca93660` | 37,379（TYPE=MAL 805） | 只取 TYPE=MAL → 去重後 792，裁剪後 307 個 t=M |
| C | 運輸署 停車場基本資料 | `td_basic_info_all.json`（有 BOM，utf-8-sig） | https://resource.data.one.gov.hk/td/carpark/basic_info_all.json | `6d95121900f50e2ccf43c0cfdc0b89071a47f8747f64f969efc4053f5df66d8f` | 560 | 全部 → 560 個 t=C |
| D | 民政事務總署 18 區界線 | `hksar_18_district_boundary.json` | https://www.had.gov.hk/psi/hong-kong-administrative-boundaries/hksar_18_district_boundary.json | `ab2123b5a48c2e39e29ce594a0c51590cf6081922ab2c5c14ff78338b788f760` | 18 | 區界 point-in-polygon；18 區本身為 t=D（面積加權形心） |

## 排除
- 地名：Village（1,299）、Hill、Bay、Island 等非 Area/Town 類；Alias 名。
- 屋邨（房委會／房協）資料集、非運輸署停車場（私人停車場、咪錶）未收錄。
- iGeoCom 無中文名之商場 2 個（ig:21212774、ig:21116399）因 normalize(zh) < 2 字被剔除。

## TLS 注意
`open.hkmapservice.gov.hk` 伺服器漏送中間證書「Hongkong Post e-Cert SSL CA 3 - 17」，Python／curl 預設會報
`CERTIFICATE_VERIFY_FAILED`。**唔好關驗證**。做法（擇一）：
1. 用瀏覽器下載上表 URL，存成 `--raw` 內 `iGeoCom_GEOJSON.zip`；或
2. 由香港郵政官方下載中間證書 https://www1.ecert.gov.hk/root/ecert_ssl_ca_3-17.crt（DER；轉 PEM：`openssl x509 -inform DER -in ecert_ssl_ca_3-17.crt -out ecert_ca3_17.pem`），
   再 `--cafile ecert_ca3_17.pem`。驗證鏈仍終於系統信任之「Hongkong Post Root CA 3」。
   中間證書 SHA-256 指紋：`CB:73:0C:3A:5F:E9:58:B8:BD:C5:B3:63:89:2C:9F:53:0B:33:F9:8A:43:CE:3C:25:78:FC:E0:96:B4:AE:23:3C`（有效至 2032-06-03）。

## 版權及鳴謝（按 DATA.GOV.HK 及 CSDI 條款）
- DATA.GOV.HK 條款要求：清楚註明資料來源、確認政府及相關機構擁有知識產權，並鳴謝政府、相關機構及 DATA.GOV.HK（https://data.gov.hk/tc/terms-and-conditions）。
- CSDI Portal 要求：鳴謝「Common Spatial Data Infrastructure (CSDI) Portal」，並註明資料按 CSDI 條款提供（連結 https://portal.csdi.gov.hk/csdi-webpage/doc/TNC）。
- App 內字串（JSON `attribution`）：`資料來源：地政總署、運輸署、民政事務總署、DATA.GOV.HK、CSDI Portal`
- 建議「數據來源」畫面全文（Step 3.4）：
  > 地名、商場資料：香港特別行政區政府地政總署（經 CSDI Portal 及香港地圖服務 2.0 提供）；停車場資料：運輸署（經 DATA.GOV.HK 提供）；區界：民政事務總署。上述資料之知識產權屬香港特別行政區政府。資料按 DATA.GOV.HK 及 CSDI Portal 使用條款提供，本 App 已作篩選及轉換，不保證準確。
