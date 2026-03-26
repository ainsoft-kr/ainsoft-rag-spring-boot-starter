import { n as onDestroy } from "../../chunks/index-server.js";
import { i as head, r as ensure_array_like, t as attr_class, v as attr, y as escape_html } from "../../chunks/server.js";
//#endregion
//#region src/routes/+page.svelte
function _page($$renderer, $$props) {
	$$renderer.component(($$renderer) => {
		let searchFormValid, siteIngestValid;
		function buildSampleDocIds() {
			const ids = [
				"product-overview",
				"ops-runbook",
				"retrieval-notes"
			];
			const yards = [
				"ulsan",
				"geoje",
				"mokpo",
				"gunsan",
				"busan"
			];
			const scenarios = [
				"block-assembly",
				"painting",
				"welding",
				"lift-plan",
				"confined-space",
				"engine-install",
				"electrical",
				"dock-check",
				"quality-audit",
				"launch-prep"
			];
			const kinds = [
				"work-permit",
				"risk-checklist",
				"toolbox-meeting",
				"incident-log",
				"graph-brief"
			];
			for (const yard of yards) for (const scenario of scenarios) for (const kind of kinds) {
				if (ids.length >= 50) return ids;
				ids.push(`${yard}-${scenario}-${kind}`);
			}
			return ids;
		}
		const sampleDefaults = {
			tenantId: "tenant-web-demo",
			principals: "group:demo",
			query: "하이브리드 검색",
			docIds: buildSampleDocIds()
		};
		const defaults = {
			tenantId: sampleDefaults.tenantId,
			docId: "notes-001",
			acl: sampleDefaults.principals,
			principals: sampleDefaults.principals,
			query: sampleDefaults.query,
			metadata: "category=notes\nsurface=ui",
			text: `아인소프트 RAG 데모 메모\n\n이 문서는 Spring Boot 데모 API를 통해 색인됩니다.\n검색 폼에서 검색 결과, ACL 필터링, 제공자 상태 진단을 함께 확인할 수 있습니다.`,
			topK: 5,
			recentProviderWindowMillis: 6e4
		};
		let ingestForm = {
			tenantId: defaults.tenantId,
			docId: defaults.docId,
			acl: defaults.acl,
			metadata: defaults.metadata,
			sourceUri: "",
			text: defaults.text
		};
		let uploadForm = {
			tenantId: defaults.tenantId,
			docId: "upload-001",
			acl: defaults.acl,
			metadata: "category=upload\nsurface=file",
			sourceUri: ""
		};
		let siteIngestForm = {
			tenantId: defaults.tenantId,
			urls: "https://example.com",
			acl: defaults.acl,
			metadata: "surface=web\nsource=website",
			allowedDomains: "example.com",
			maxPages: 25,
			maxDepth: 1,
			sameHostOnly: true,
			respectRobotsTxt: true,
			incrementalIngest: true,
			charset: "UTF-8",
			userAgent: "AinsoftRagBot/1.0"
		};
		let searchForm = {
			tenantId: defaults.tenantId,
			principals: defaults.principals,
			query: defaults.query,
			topK: defaults.topK,
			filter: "",
			openSource: false,
			recentProviderWindowMillis: defaults.recentProviderWindowMillis
		};
		let statsTenantId = defaults.tenantId;
		let notifications = [];
		const noticeTimers = /* @__PURE__ */ new Map();
		let loading = {
			sample: false,
			ingest: false,
			siteIngest: false,
			upload: false,
			search: false,
			answer: false,
			answerStream: false,
			diagnose: false,
			stats: false,
			health: false
		};
		let sampleResponse = {
			tenantId: sampleDefaults.tenantId,
			principals: sampleDefaults.principals,
			suggestedQuery: sampleDefaults.query,
			docIds: sampleDefaults.docIds,
			status: "loaded"
		};
		let ingestResponse = null;
		let uploadResponse = null;
		let siteIngestResponse = null;
		let siteIngestStreamEvents = [];
		let siteIngestProgress = {
			phase: "idle",
			message: "대기 중",
			current: 0,
			total: 0
		};
		let siteIngestResultStatusFilter = "all";
		let siteIngestResultSort = "status";
		let searchResponse = null;
		let answerResponse = null;
		let answerStreamEvents = [];
		let diagnoseResponse = null;
		let statsResponse = null;
		let healthResponse = null;
		let activeTab = "search";
		const sampleDocPreviewLimit = 8;
		function toMap(value) {
			return value.split("\n").map((line) => line.trim()).filter(Boolean).reduce((acc, line) => {
				const separatorIndex = line.indexOf("=");
				if (separatorIndex > 0 && separatorIndex < line.length - 1) {
					const key = line.slice(0, separatorIndex).trim();
					const mapValue = line.slice(separatorIndex + 1).trim();
					if (key && mapValue) acc[key] = mapValue;
				}
				return acc;
			}, {});
		}
		function toLines(value) {
			return value.split(/\r?\n/).map((item) => item.trim()).filter(Boolean);
		}
		function parseIntegerInput(value, fallback, min = Number.NEGATIVE_INFINITY) {
			const parsed = Number(value);
			if (!Number.isInteger(parsed) || parsed < min) return fallback;
			return parsed;
		}
		function isIntegerInput(value, min = Number.NEGATIVE_INFINITY) {
			const parsed = Number(value);
			return value !== "" && Number.isInteger(parsed) && parsed >= min;
		}
		function isNonEmptyListInput(value) {
			return toLines(value).length > 0;
		}
		function sampleDocPreview(docIds) {
			return docIds.slice(0, sampleDocPreviewLimit);
		}
		function buildSiteIngestPayload() {
			return {
				tenantId: siteIngestForm.tenantId,
				urls: toLines(siteIngestForm.urls),
				acl: toLines(siteIngestForm.acl),
				metadata: toMap(siteIngestForm.metadata),
				allowedDomains: toLines(siteIngestForm.allowedDomains),
				respectRobotsTxt: siteIngestForm.respectRobotsTxt,
				incrementalIngest: siteIngestForm.incrementalIngest,
				userAgent: siteIngestForm.userAgent.trim() || "AinsoftRagBot/1.0",
				maxPages: parseIntegerInput(siteIngestForm.maxPages, 25, 1),
				maxDepth: parseIntegerInput(siteIngestForm.maxDepth, 1, 0),
				sameHostOnly: siteIngestForm.sameHostOnly,
				charset: siteIngestForm.charset.trim() || "UTF-8"
			};
		}
		function rankSiteResultStatus(status) {
			return {
				changed: 0,
				ingested: 1,
				skipped: 2,
				failed: 3
			}[status] ?? 4;
		}
		function compareSiteResults(left, right) {
			if (siteIngestResultSort === "depth") {
				const depthDelta = (left.depth ?? Number.MAX_SAFE_INTEGER) - (right.depth ?? Number.MAX_SAFE_INTEGER);
				if (depthDelta !== 0) return depthDelta;
				return (left.title ?? left.docId).localeCompare(right.title ?? right.docId);
			}
			if (siteIngestResultSort === "title") return (left.title ?? left.docId).localeCompare(right.title ?? right.docId);
			if (siteIngestResultSort === "order") return 0;
			const statusDelta = rankSiteResultStatus(left.status) - rankSiteResultStatus(right.status);
			if (statusDelta !== 0) return statusDelta;
			const depthDelta = (left.depth ?? Number.MAX_SAFE_INTEGER) - (right.depth ?? Number.MAX_SAFE_INTEGER);
			if (depthDelta !== 0) return depthDelta;
			return (left.title ?? left.docId).localeCompare(right.title ?? right.docId);
		}
		onDestroy(() => {
			for (const timer of noticeTimers.values()) clearTimeout(timer);
			noticeTimers.clear();
		});
		const statCards = [
			{
				label: "Indexed Docs",
				value: () => statsResponse?.docs ?? 0,
				helper: "현재 테넌트 기준 문서 수"
			},
			{
				label: "Indexed Chunks",
				value: () => statsResponse?.chunks ?? 0,
				helper: "색인된 전체 청크 수"
			},
			{
				label: "Provider Calls",
				value: () => statsResponse?.providerTelemetry?.requestCount ?? 0,
				helper: "누적 임베딩/리랭크 호출"
			},
			{
				label: "Cache Hit Rate",
				value: () => `${statsResponse?.statsCacheHitRatePct ?? 0}%`,
				helper: "통계 캐시 적중률"
			}
		];
		$: searchFormValid = isIntegerInput(searchForm.topK, 1) && isIntegerInput(searchForm.recentProviderWindowMillis, 0);
		$: siteIngestValid = siteIngestForm.tenantId.trim().length > 0 && isNonEmptyListInput(siteIngestForm.urls) && isNonEmptyListInput(siteIngestForm.acl) && isIntegerInput(siteIngestForm.maxPages, 1) && isIntegerInput(siteIngestForm.maxDepth, 0);
		$: (siteIngestResponse?.results ?? []).filter((result) => siteIngestResultStatusFilter === "all" || result.status === siteIngestResultStatusFilter).slice().sort(compareSiteResults);
		head("1uha8ag", $$renderer, ($$renderer) => {
			$$renderer.title(($$renderer) => {
				$$renderer.push(`<title>Ainsoft RAG Demo</title>`);
			});
			$$renderer.push(`<meta name="description" content="Spring Boot static hosting demo for Ainsoft RAG with a SvelteKit frontend."/>`);
		});
		$$renderer.push(`<div class="shell svelte-1uha8ag" data-testid="demo-shell"><div class="ambient ambient-left svelte-1uha8ag"></div> <div class="ambient ambient-right svelte-1uha8ag"></div> <section class="hero svelte-1uha8ag"><div class="hero-copy svelte-1uha8ag"><p class="eyebrow svelte-1uha8ag">AINSOFT RAG SPRING BOOT DEMO</p> <h1 class="svelte-1uha8ag">색인, 검색, 진단을 테스트하기 위한 데모사이트 입니다.</h1> <p class="lede svelte-1uha8ag">이 화면은 Spring Boot가 직접 제공하는 정적 파일입니다. 문서 색인, 파일 업로드, 검색,
        진단, 제공자 상태를 즉시 검증할 수 있습니다.</p> <div class="sample-banner svelte-1uha8ag" data-testid="sample-banner"><div><span class="sample-label svelte-1uha8ag">Sample Tenant</span> <strong class="svelte-1uha8ag">${escape_html(sampleResponse.tenantId)}</strong></div> <div><span class="sample-label svelte-1uha8ag">Suggested Query</span> <strong class="svelte-1uha8ag">${escape_html(sampleResponse.suggestedQuery)}</strong></div> <button data-testid="clear-sample-button"${attr("disabled", loading.sample, true)} class="svelte-1uha8ag">${escape_html(loading.sample ? "처리 중..." : "Clear Data")}</button></div> `);
		$$renderer.push("<!--[-1-->");
		$$renderer.push(`<!--]--> `);
		if (sampleResponse.docIds.length) {
			$$renderer.push("<!--[0-->");
			$$renderer.push(`<p class="muted">샘플 문서 ${escape_html(sampleResponse.docIds.length)}개가 준비되어 있습니다.</p> <div class="chip-field compact svelte-1uha8ag"><!--[-->`);
			const each_array = ensure_array_like(sampleDocPreview(sampleResponse.docIds));
			for (let $$index = 0, $$length = each_array.length; $$index < $$length; $$index++) {
				let docId = each_array[$$index];
				$$renderer.push(`<span class="svelte-1uha8ag">${escape_html(docId)}</span>`);
			}
			$$renderer.push(`<!--]--> `);
			if (sampleResponse.docIds.length > sampleDocPreviewLimit) {
				$$renderer.push("<!--[0-->");
				$$renderer.push(`<span class="svelte-1uha8ag">외 ${escape_html(sampleResponse.docIds.length - sampleDocPreviewLimit)}개</span>`);
			} else $$renderer.push("<!--[-1-->");
			$$renderer.push(`<!--]--></div>`);
		} else {
			$$renderer.push("<!--[-1-->");
			$$renderer.push(`<p class="empty sample-empty svelte-1uha8ag">${escape_html(sampleResponse.status === "cleared" ? "샘플 데이터가 삭제되었습니다." : "샘플 데이터가 비어 있습니다.")}</p>`);
		}
		$$renderer.push(`<!--]--></div> <div class="hero-stats svelte-1uha8ag"><!--[-->`);
		const each_array_1 = ensure_array_like(statCards);
		for (let $$index_1 = 0, $$length = each_array_1.length; $$index_1 < $$length; $$index_1++) {
			let card = each_array_1[$$index_1];
			$$renderer.push(`<article class="stat-card svelte-1uha8ag"${attr("data-testid", `stat-${card.label.toLowerCase().replace(/\s+/g, "-")}`)}><span class="svelte-1uha8ag">${escape_html(card.label)}</span> <strong class="svelte-1uha8ag">${escape_html(card.value())}</strong> <small class="svelte-1uha8ag">${escape_html(card.helper)}</small></article>`);
		}
		$$renderer.push(`<!--]--></div></section> `);
		if (notifications.length) {
			$$renderer.push("<!--[0-->");
			$$renderer.push(`<section class="notices svelte-1uha8ag" data-testid="notifications" aria-live="polite" aria-atomic="true"><!--[-->`);
			const each_array_2 = ensure_array_like(notifications);
			for (let $$index_2 = 0, $$length = each_array_2.length; $$index_2 < $$length; $$index_2++) {
				let notice = each_array_2[$$index_2];
				$$renderer.push(`<div${attr_class("notice svelte-1uha8ag", void 0, {
					"success": notice.kind === "success",
					"error": notice.kind === "error"
				})}><span class="svelte-1uha8ag">${escape_html(notice.kind === "success" ? "OK" : "ERR")}</span> <div class="notice-copy svelte-1uha8ag">`);
				if (notice.source) {
					$$renderer.push("<!--[0-->");
					$$renderer.push(`<small class="svelte-1uha8ag">${escape_html(notice.source)}</small>`);
				} else $$renderer.push("<!--[-1-->");
				$$renderer.push(`<!--]--> <p>${escape_html(notice.message)}</p></div></div>`);
			}
			$$renderer.push(`<!--]--></section>`);
		} else $$renderer.push("<!--[-1-->");
		$$renderer.push(`<!--]--> <section class="workspace svelte-1uha8ag"><div class="workspace-tabs svelte-1uha8ag" role="tablist" aria-label="Demo panels"><button type="button"${attr_class("svelte-1uha8ag", void 0, { "active": activeTab === "search" })}>검색과 진단</button> <button type="button"${attr_class("svelte-1uha8ag", void 0, { "active": activeTab === "ingest" })}>텍스트 색인</button> <button type="button"${attr_class("svelte-1uha8ag", void 0, { "active": activeTab === "siteIngest" })}>사이트 색인</button> <button type="button"${attr_class("svelte-1uha8ag", void 0, { "active": activeTab === "upload" })}>파일 업로드</button> <button type="button"${attr_class("svelte-1uha8ag", void 0, { "active": activeTab === "operations" })}>운영 상태</button></div> `);
		if (activeTab === "ingest") {
			$$renderer.push("<!--[0-->");
			$$renderer.push(`<div class="tab-layout svelte-1uha8ag"><div class="controls svelte-1uha8ag"><article class="panel svelte-1uha8ag"><div class="panel-header svelte-1uha8ag"><h2 class="svelte-1uha8ag">텍스트 색인</h2> <button data-testid="ingest-submit"${attr("disabled", loading.ingest, true)} class="svelte-1uha8ag">${escape_html(loading.ingest ? "처리 중..." : "JSON Ingest")}</button></div> <div class="grid two svelte-1uha8ag"><label class="svelte-1uha8ag"><span class="svelte-1uha8ag">Tenant</span> <input${attr("value", ingestForm.tenantId)} data-testid="ingest-tenant" class="svelte-1uha8ag"/></label> <label class="svelte-1uha8ag"><span class="svelte-1uha8ag">Doc ID</span> <input${attr("value", ingestForm.docId)} data-testid="ingest-doc-id" class="svelte-1uha8ag"/></label></div> <label class="svelte-1uha8ag"><span class="svelte-1uha8ag">ACL (comma separated)</span> <input${attr("value", ingestForm.acl)} class="svelte-1uha8ag"/></label> <label class="svelte-1uha8ag"><span class="svelte-1uha8ag">Metadata (\`key=value\`, one per line)</span> <textarea rows="3" class="svelte-1uha8ag">`);
			const $$body = escape_html(ingestForm.metadata);
			if ($$body) $$renderer.push(`${$$body}`);
			$$renderer.push(`</textarea></label> <label class="svelte-1uha8ag"><span class="svelte-1uha8ag">Source URI</span> <input${attr("value", ingestForm.sourceUri)} placeholder="optional" class="svelte-1uha8ag"/></label> <label class="svelte-1uha8ag"><span class="svelte-1uha8ag">Document Text</span> <textarea data-testid="ingest-text" rows="12" class="svelte-1uha8ag">`);
			const $$body_1 = escape_html(ingestForm.text);
			if ($$body_1) $$renderer.push(`${$$body_1}`);
			$$renderer.push(`</textarea></label></article></div> <div class="results svelte-1uha8ag"><article class="panel result-panel svelte-1uha8ag"><div class="panel-header svelte-1uha8ag"><h2 class="svelte-1uha8ag">최근 색인 응답</h2> <span class="pill svelte-1uha8ag">${escape_html(ingestResponse?.status ?? "idle")}</span></div> `);
			$$renderer.push("<!--[-1-->");
			$$renderer.push(`<p class="empty svelte-1uha8ag">아직 색인 요청이 없습니다.</p>`);
			$$renderer.push(`<!--]--></article></div></div>`);
		} else if (activeTab === "siteIngest") {
			$$renderer.push("<!--[1-->");
			$$renderer.push(`<div class="tab-layout svelte-1uha8ag"><div class="controls svelte-1uha8ag"><article class="panel svelte-1uha8ag"><div class="panel-header svelte-1uha8ag"><h2 class="svelte-1uha8ag">사이트 색인</h2> <button data-testid="site-ingest-submit"${attr("disabled", loading.siteIngest || !siteIngestValid, true)} class="svelte-1uha8ag">${escape_html(loading.siteIngest ? "색인 중..." : "Site Ingest")}</button></div> <div class="grid two svelte-1uha8ag"><label class="svelte-1uha8ag"><span class="svelte-1uha8ag">Tenant</span> <input${attr("value", siteIngestForm.tenantId)} data-testid="site-ingest-tenant" class="svelte-1uha8ag"/></label> <label class="svelte-1uha8ag"><span class="svelte-1uha8ag">Max Pages</span> <input${attr("value", siteIngestForm.maxPages)} min="1" type="number" class="svelte-1uha8ag"/></label></div> <div class="grid two svelte-1uha8ag"><label class="svelte-1uha8ag"><span class="svelte-1uha8ag">Max Depth</span> <input${attr("value", siteIngestForm.maxDepth)} min="0" type="number" class="svelte-1uha8ag"/></label> <label class="svelte-1uha8ag"><span class="svelte-1uha8ag">Charset</span> <input${attr("value", siteIngestForm.charset)} class="svelte-1uha8ag"/></label></div> <label class="svelte-1uha8ag"><span class="svelte-1uha8ag">Seed URLs (\`URL\` per line)</span> <textarea rows="3" class="svelte-1uha8ag">`);
			const $$body_2 = escape_html(siteIngestForm.urls);
			if ($$body_2) $$renderer.push(`${$$body_2}`);
			$$renderer.push(`</textarea></label> <label class="svelte-1uha8ag"><span class="svelte-1uha8ag">Allowed Domains (\`domain\` per line)</span> <textarea rows="3" class="svelte-1uha8ag">`);
			const $$body_3 = escape_html(siteIngestForm.allowedDomains);
			if ($$body_3) $$renderer.push(`${$$body_3}`);
			$$renderer.push(`</textarea></label> <label class="svelte-1uha8ag"><span class="svelte-1uha8ag">ACL (\`principal\` per line)</span> <textarea rows="3" class="svelte-1uha8ag">`);
			const $$body_4 = escape_html(siteIngestForm.acl);
			if ($$body_4) $$renderer.push(`${$$body_4}`);
			$$renderer.push(`</textarea></label> <label class="svelte-1uha8ag"><span class="svelte-1uha8ag">Metadata (\`key=value\`, one per line)</span> <textarea rows="3" class="svelte-1uha8ag">`);
			const $$body_5 = escape_html(siteIngestForm.metadata);
			if ($$body_5) $$renderer.push(`${$$body_5}`);
			$$renderer.push(`</textarea></label> <div class="grid two compact svelte-1uha8ag"><label class="checkbox svelte-1uha8ag"><span class="svelte-1uha8ag">Same Host Only</span> <input${attr("checked", siteIngestForm.sameHostOnly, true)} type="checkbox" class="svelte-1uha8ag"/></label> <label class="checkbox svelte-1uha8ag"><span class="svelte-1uha8ag">Respect robots.txt</span> <input${attr("checked", siteIngestForm.respectRobotsTxt, true)} type="checkbox" class="svelte-1uha8ag"/></label></div> <div class="grid two compact svelte-1uha8ag"><label class="checkbox svelte-1uha8ag"><span class="svelte-1uha8ag">Incremental Ingest</span> <input${attr("checked", siteIngestForm.incrementalIngest, true)} type="checkbox" class="svelte-1uha8ag"/></label> <label class="svelte-1uha8ag"><span class="svelte-1uha8ag">User Agent</span> <input${attr("value", siteIngestForm.userAgent)} class="svelte-1uha8ag"/></label></div></article></div> <div class="results svelte-1uha8ag"><article class="panel result-panel svelte-1uha8ag"><div class="panel-header svelte-1uha8ag"><h2 class="svelte-1uha8ag">사이트 색인 결과</h2> <span class="pill svelte-1uha8ag">${escape_html(siteIngestResponse?.status ?? "idle")}</span></div> `);
			$$renderer.push("<!--[-1-->");
			$$renderer.push(`<p class="empty svelte-1uha8ag">아직 사이트 색인 요청이 없습니다.</p>`);
			$$renderer.push(`<!--]--></article> <details class="panel result-panel debug-panel svelte-1uha8ag"${attr("open", siteIngestStreamEvents.length > 0, true)}><summary class="panel-header svelte-1uha8ag"><h2 class="svelte-1uha8ag">Site Ingest Timeline</h2> <span class="pill svelte-1uha8ag">${escape_html(siteIngestProgress.phase)}</span></summary> <div class="metric-list compact-summary svelte-1uha8ag"><div class="svelte-1uha8ag"><span class="svelte-1uha8ag">Progress</span> <strong class="svelte-1uha8ag">${escape_html(siteIngestProgress.current)} / ${escape_html(siteIngestProgress.total)}</strong></div> <div class="svelte-1uha8ag"><span class="svelte-1uha8ag">Message</span> <strong class="svelte-1uha8ag">${escape_html(siteIngestProgress.message)}</strong></div> <div class="svelte-1uha8ag"><span class="svelte-1uha8ag">Events</span> <strong class="svelte-1uha8ag">${escape_html(siteIngestStreamEvents.length)}</strong></div> <div class="svelte-1uha8ag"><span class="svelte-1uha8ag">Tenant</span> <strong class="svelte-1uha8ag">${escape_html(siteIngestForm.tenantId)}</strong></div></div> `);
			if (siteIngestStreamEvents.length) {
				$$renderer.push("<!--[0-->");
				$$renderer.push(`<div class="stream-list svelte-1uha8ag"><!--[-->`);
				const each_array_5 = ensure_array_like([...siteIngestStreamEvents].reverse().slice(0, 8));
				for (let $$index_5 = 0, $$length = each_array_5.length; $$index_5 < $$length; $$index_5++) {
					let event = each_array_5[$$index_5];
					$$renderer.push(`<article class="stream-event svelte-1uha8ag"><div class="hit-topline svelte-1uha8ag"><strong class="svelte-1uha8ag">${escape_html(event.event)}</strong> <span>${escape_html(event.phase ?? "n/a")}</span></div> <p>${escape_html(event.message ?? event.status ?? "no message")}</p> `);
					if (event.url) {
						$$renderer.push("<!--[0-->");
						$$renderer.push(`<div class="tag-row svelte-1uha8ag"><span class="svelte-1uha8ag">${escape_html(event.url)}</span> `);
						if (event.depth !== null && event.depth !== void 0) {
							$$renderer.push("<!--[0-->");
							$$renderer.push(`<span class="svelte-1uha8ag">depth ${escape_html(event.depth)}</span>`);
						} else $$renderer.push("<!--[-1-->");
						$$renderer.push(`<!--]--> `);
						if (event.current !== null && event.current !== void 0 && event.total !== null && event.total !== void 0) {
							$$renderer.push("<!--[0-->");
							$$renderer.push(`<span class="svelte-1uha8ag">${escape_html(event.current)}/${escape_html(event.total)}</span>`);
						} else $$renderer.push("<!--[-1-->");
						$$renderer.push(`<!--]--></div>`);
					} else $$renderer.push("<!--[-1-->");
					$$renderer.push(`<!--]--></article>`);
				}
				$$renderer.push(`<!--]--></div>`);
			} else {
				$$renderer.push("<!--[-1-->");
				$$renderer.push(`<p class="empty svelte-1uha8ag">사이트 색인을 실행하면 진행 이벤트가 여기에 표시됩니다.</p>`);
			}
			$$renderer.push(`<!--]--> <details class="debug-json svelte-1uha8ag"><summary class="svelte-1uha8ag">Outgoing JSON</summary> <pre class="svelte-1uha8ag">${escape_html(JSON.stringify(buildSiteIngestPayload(), null, 2))}</pre></details></details></div></div>`);
		} else if (activeTab === "upload") {
			$$renderer.push("<!--[2-->");
			$$renderer.push(`<div class="tab-layout svelte-1uha8ag"><div class="controls svelte-1uha8ag"><article class="panel svelte-1uha8ag"><div class="panel-header svelte-1uha8ag"><h2 class="svelte-1uha8ag">파일 업로드</h2> <button data-testid="upload-submit"${attr("disabled", loading.upload, true)} class="svelte-1uha8ag">${escape_html(loading.upload ? "처리 중..." : "Multipart Upload")}</button></div> <div class="grid two svelte-1uha8ag"><label class="svelte-1uha8ag"><span class="svelte-1uha8ag">Tenant</span> <input${attr("value", uploadForm.tenantId)} data-testid="upload-tenant" class="svelte-1uha8ag"/></label> <label class="svelte-1uha8ag"><span class="svelte-1uha8ag">Doc ID</span> <input${attr("value", uploadForm.docId)} data-testid="upload-doc-id" class="svelte-1uha8ag"/></label></div> <label class="svelte-1uha8ag"><span class="svelte-1uha8ag">ACL</span> <input${attr("value", uploadForm.acl)} class="svelte-1uha8ag"/></label> <label class="svelte-1uha8ag"><span class="svelte-1uha8ag">Metadata</span> <textarea rows="3" class="svelte-1uha8ag">`);
			const $$body_6 = escape_html(uploadForm.metadata);
			if ($$body_6) $$renderer.push(`${$$body_6}`);
			$$renderer.push(`</textarea></label> <label class="svelte-1uha8ag"><span class="svelte-1uha8ag">Source URI</span> <input${attr("value", uploadForm.sourceUri)} placeholder="optional" class="svelte-1uha8ag"/></label> <label class="file-picker svelte-1uha8ag"><span class="svelte-1uha8ag">File</span> <input data-testid="upload-file-input" type="file" class="svelte-1uha8ag"/> <small data-testid="upload-file-name" class="svelte-1uha8ag">${escape_html("선택된 파일 없음")}</small></label></article></div> <div class="results svelte-1uha8ag"><article class="panel result-panel svelte-1uha8ag"><div class="panel-header svelte-1uha8ag"><h2 class="svelte-1uha8ag">최근 파일 업로드 응답</h2> <span class="pill svelte-1uha8ag">${escape_html(uploadResponse?.status ?? "idle")}</span></div> `);
			$$renderer.push("<!--[-1-->");
			$$renderer.push(`<p class="empty svelte-1uha8ag">아직 업로드 요청이 없습니다.</p>`);
			$$renderer.push(`<!--]--></article></div></div>`);
		} else if (activeTab === "search") {
			$$renderer.push("<!--[3-->");
			$$renderer.push(`<div class="tab-layout search-layout svelte-1uha8ag"><div class="controls svelte-1uha8ag"><article class="panel svelte-1uha8ag"><div class="panel-header svelte-1uha8ag"><h2 class="svelte-1uha8ag">검색과 진단</h2> <div class="button-row svelte-1uha8ag"><button data-testid="search-submit"${attr("disabled", loading.search || !searchFormValid, true)} class="svelte-1uha8ag">${escape_html(loading.search ? "검색 중..." : "Search")}</button> <button data-testid="answer-submit"${attr("disabled", loading.answer || !searchFormValid, true)} class="svelte-1uha8ag">${escape_html(loading.answer ? "생성 중..." : "Answer")}</button> <button class="ghost svelte-1uha8ag" data-testid="answer-stream-submit"${attr("disabled", loading.answerStream || !searchFormValid, true)}>${escape_html(loading.answerStream ? "스트리밍 중..." : "Stream")}</button> <button class="ghost svelte-1uha8ag" data-testid="diagnose-submit"${attr("disabled", loading.diagnose || !searchFormValid, true)}>${escape_html(loading.diagnose ? "진단 중..." : "Diagnose")}</button></div></div> <div class="grid two svelte-1uha8ag"><label class="svelte-1uha8ag"><span class="svelte-1uha8ag">Tenant</span> <input${attr("value", searchForm.tenantId)} data-testid="search-tenant" class="svelte-1uha8ag"/></label> <label class="svelte-1uha8ag"><span class="svelte-1uha8ag">Top K</span> <input${attr("value", searchForm.topK)} min="1" max="20" required="" type="number" class="svelte-1uha8ag"/></label></div> <label class="svelte-1uha8ag"><span class="svelte-1uha8ag">Principals</span> <input${attr("value", searchForm.principals)} data-testid="search-principals" class="svelte-1uha8ag"/></label> <label class="svelte-1uha8ag"><span class="svelte-1uha8ag">Query</span> <input${attr("value", searchForm.query)} data-testid="search-query" class="svelte-1uha8ag"/></label> <label class="svelte-1uha8ag"><span class="svelte-1uha8ag">Metadata Filter (\`key=value\`, one per line)</span> <textarea rows="3" class="svelte-1uha8ag">`);
			const $$body_7 = escape_html(searchForm.filter);
			if ($$body_7) $$renderer.push(`${$$body_7}`);
			$$renderer.push(`</textarea></label> <div class="grid two compact svelte-1uha8ag"><label class="svelte-1uha8ag"><span class="svelte-1uha8ag">Provider Window (ms)</span> <input${attr("value", searchForm.recentProviderWindowMillis)} required="" type="number" min="0" class="svelte-1uha8ag"/></label> <label class="checkbox svelte-1uha8ag"><span class="svelte-1uha8ag">Open Source Snippet</span> <input${attr("checked", searchForm.openSource, true)} type="checkbox" class="svelte-1uha8ag"/></label></div></article></div> <div class="results svelte-1uha8ag"><article class="panel result-panel svelte-1uha8ag" data-testid="answer-panel"><div class="panel-header svelte-1uha8ag"><h2 class="svelte-1uha8ag">답변</h2> <span class="pill svelte-1uha8ag">${escape_html(answerResponse?.schemaVersion ?? "idle")}</span></div> `);
			$$renderer.push("<!--[-1-->");
			$$renderer.push(`<p class="empty svelte-1uha8ag">Answer 버튼을 누르면 구조화된 답변이 표시됩니다.</p>`);
			$$renderer.push(`<!--]--></article> <article class="panel result-panel svelte-1uha8ag" data-testid="answer-stream-panel"><div class="panel-header svelte-1uha8ag"><h2 class="svelte-1uha8ag">Answer Stream</h2> <span class="pill svelte-1uha8ag">${escape_html(answerStreamEvents.length)} events</span></div> `);
			if (answerStreamEvents.length) {
				$$renderer.push("<!--[0-->");
				$$renderer.push(`<div class="stream-list svelte-1uha8ag"><!--[-->`);
				const each_array_10 = ensure_array_like(answerStreamEvents);
				for (let $$index_10 = 0, $$length = each_array_10.length; $$index_10 < $$length; $$index_10++) {
					let event = each_array_10[$$index_10];
					$$renderer.push(`<article class="stream-event svelte-1uha8ag"><div class="hit-topline svelte-1uha8ag"><strong class="svelte-1uha8ag">${escape_html(event.event)}</strong></div> <pre class="svelte-1uha8ag">${escape_html(event.data)}</pre></article>`);
				}
				$$renderer.push(`<!--]--></div>`);
			} else {
				$$renderer.push("<!--[-1-->");
				$$renderer.push(`<p class="empty svelte-1uha8ag">Stream 버튼을 누르면 SSE 이벤트가 여기에 표시됩니다.</p>`);
			}
			$$renderer.push(`<!--]--></article> <article class="panel result-panel svelte-1uha8ag" data-testid="search-results-panel"><div class="panel-header svelte-1uha8ag"><h2 class="svelte-1uha8ag">검색 결과</h2> <span class="pill svelte-1uha8ag">${escape_html(searchResponse?.meta?.resultCount ?? 0)} hits</span></div> `);
			if (searchResponse?.hits?.length) {
				$$renderer.push("<!--[0-->");
				$$renderer.push(`<div class="hit-list svelte-1uha8ag" data-testid="search-results"><!--[-->`);
				const each_array_11 = ensure_array_like(searchResponse.hits);
				for (let $$index_12 = 0, $$length = each_array_11.length; $$index_12 < $$length; $$index_12++) {
					let hit = each_array_11[$$index_12];
					$$renderer.push(`<article class="hit-card svelte-1uha8ag" data-testid="search-hit"><div class="hit-topline svelte-1uha8ag"><strong class="svelte-1uha8ag">${escape_html(hit.docId)}</strong> <span>${escape_html(hit.score.toFixed(4))}</span></div> <p class="svelte-1uha8ag">${escape_html(hit.sourceSnippet ?? hit.text ?? "본문이 저장되지 않았습니다.")}</p> <div class="tag-row svelte-1uha8ag"><span class="svelte-1uha8ag">${escape_html(hit.contentKind)}</span> `);
					if (hit.page !== null) {
						$$renderer.push("<!--[0-->");
						$$renderer.push(`<span class="svelte-1uha8ag">page ${escape_html(hit.page)}</span>`);
					} else $$renderer.push("<!--[-1-->");
					$$renderer.push(`<!--]--> <!--[-->`);
					const each_array_12 = ensure_array_like(Object.entries(hit.metadata));
					for (let $$index_11 = 0, $$length = each_array_12.length; $$index_11 < $$length; $$index_11++) {
						let [key, value] = each_array_12[$$index_11];
						$$renderer.push(`<span class="svelte-1uha8ag">${escape_html(key)}: ${escape_html(value)}</span>`);
					}
					$$renderer.push(`<!--]--></div></article>`);
				}
				$$renderer.push(`<!--]--></div>`);
			} else {
				$$renderer.push("<!--[-1-->");
				$$renderer.push(`<p class="empty svelte-1uha8ag">검색을 실행하면 결과가 여기에 표시됩니다.</p>`);
			}
			$$renderer.push(`<!--]--></article> <article class="panel result-panel svelte-1uha8ag"><div class="panel-header svelte-1uha8ag"><h2 class="svelte-1uha8ag">검색 진단</h2> <span class="pill svelte-1uha8ag">${escape_html(diagnoseResponse?.derivedEmptyReason ?? "idle")}</span></div> `);
			$$renderer.push("<!--[-1-->");
			$$renderer.push(`<p class="empty svelte-1uha8ag">진단 버튼을 누르면 검색 경로 분석이 표시됩니다.</p>`);
			$$renderer.push(`<!--]--></article></div></div>`);
		} else if (activeTab === "operations") {
			$$renderer.push("<!--[4-->");
			$$renderer.push(`<div class="tab-layout svelte-1uha8ag"><div class="controls svelte-1uha8ag"><article class="panel svelte-1uha8ag"><div class="panel-header svelte-1uha8ag"><h2 class="svelte-1uha8ag">운영 상태</h2> <div class="button-row svelte-1uha8ag"><button${attr("disabled", loading.stats, true)} class="svelte-1uha8ag">${escape_html(loading.stats ? "새로고침 중..." : "Stats")}</button> <button class="ghost svelte-1uha8ag"${attr("disabled", loading.health, true)}>${escape_html(loading.health ? "새로고침 중..." : "Provider")}</button></div></div> <label class="svelte-1uha8ag"><span class="svelte-1uha8ag">Stats Tenant</span> <input${attr("value", statsTenantId)} class="svelte-1uha8ag"/></label> <div class="metric-list svelte-1uha8ag"><div class="svelte-1uha8ag"><span class="svelte-1uha8ag">Snapshots</span> <strong class="svelte-1uha8ag">${escape_html(statsResponse?.snapshotCount ?? 0)}</strong></div> <div class="svelte-1uha8ag"><span class="svelte-1uha8ag">Index Size</span> <strong class="svelte-1uha8ag">${escape_html(statsResponse?.indexSizeBytes ?? 0)} B</strong></div> <div class="svelte-1uha8ag"><span class="svelte-1uha8ag">Recent Window</span> <strong class="svelte-1uha8ag">${escape_html(healthResponse?.recentProviderWindowMillis ?? 0)} ms</strong></div> <div class="svelte-1uha8ag"><span class="svelte-1uha8ag">Fallback Used</span> <strong class="svelte-1uha8ag">${escape_html(searchResponse?.meta?.providerFallbackApplied ? "yes" : "no")}</strong></div></div></article></div> <div class="results svelte-1uha8ag"><article class="panel result-panel compact-summary svelte-1uha8ag"><div class="panel-header svelte-1uha8ag"><h2 class="svelte-1uha8ag">운영 요약</h2> <span class="pill svelte-1uha8ag">${escape_html(statsResponse?.tenantId ?? statsTenantId)}</span></div> `);
			$$renderer.push("<!--[-1-->");
			$$renderer.push(`<p class="empty svelte-1uha8ag">Provider 상태를 불러오면 호출량과 성공률이 표시됩니다.</p>`);
			$$renderer.push(`<!--]--></article></div></div>`);
		} else $$renderer.push("<!--[-1-->");
		$$renderer.push(`<!--]--></section></div>`);
	});
}
//#endregion
export { _page as default };
