<svelte:head>
  <title>Ainsoft RAG Demo</title>
  <meta
    name="description"
    content="Spring Boot static hosting demo for Ainsoft RAG with a SvelteKit frontend."
  />
</svelte:head>

<script>
  import { onDestroy, onMount } from 'svelte';
  import { apiGet, apiPost, apiUpload } from '$lib/api';

  function buildSampleDocIds() {
    const ids = ['product-overview', 'ops-runbook', 'retrieval-notes'];
    const yards = ['ulsan', 'geoje', 'mokpo', 'gunsan', 'busan'];
    const scenarios = [
      'block-assembly',
      'painting',
      'welding',
      'lift-plan',
      'confined-space',
      'engine-install',
      'electrical',
      'dock-check',
      'quality-audit',
      'launch-prep'
    ];
    const kinds = ['work-permit', 'risk-checklist', 'toolbox-meeting', 'incident-log', 'graph-brief'];

    for (const yard of yards) {
      for (const scenario of scenarios) {
        for (const kind of kinds) {
          if (ids.length >= 50) {
            return ids;
          }
          ids.push(`${yard}-${scenario}-${kind}`);
        }
      }
    }
    return ids;
  }

  const sampleDefaults = {
    tenantId: 'tenant-web-demo',
    principals: 'group:demo',
    query: '하이브리드 검색',
    docIds: buildSampleDocIds()
  };

  const defaults = {
    tenantId: sampleDefaults.tenantId,
    docId: 'notes-001',
    acl: sampleDefaults.principals,
    principals: sampleDefaults.principals,
    query: sampleDefaults.query,
    metadata: 'category=notes\nsurface=ui',
    text: `아인소프트 RAG 데모 메모\n\n이 문서는 Spring Boot 데모 API를 통해 색인됩니다.\n검색 폼에서 검색 결과, ACL 필터링, 제공자 상태 진단을 함께 확인할 수 있습니다.`,
    topK: 5,
    recentProviderWindowMillis: 60000
  };

  let ingestForm = {
    tenantId: defaults.tenantId,
    docId: defaults.docId,
    acl: defaults.acl,
    metadata: defaults.metadata,
    sourceUri: '',
    text: defaults.text
  };

  let uploadForm = {
    tenantId: defaults.tenantId,
    docId: 'upload-001',
    acl: defaults.acl,
    metadata: 'category=upload\nsurface=file',
    sourceUri: ''
  };

  let siteIngestForm = {
    tenantId: defaults.tenantId,
    urls: 'https://example.com',
    acl: defaults.acl,
    metadata: 'surface=web\nsource=website',
    allowedDomains: 'example.com',
    maxPages: 25,
    maxDepth: 1,
    sameHostOnly: true,
    respectRobotsTxt: true,
    incrementalIngest: true,
    charset: 'UTF-8',
    userAgent: 'AinsoftRagBot/1.0'
  };

  let searchForm = {
    tenantId: defaults.tenantId,
    principals: defaults.principals,
    query: defaults.query,
    topK: defaults.topK,
    filter: '',
    openSource: false,
    recentProviderWindowMillis: defaults.recentProviderWindowMillis
  };

  let statsTenantId = defaults.tenantId;
  let selectedFile = null;
  let notifications = [];
  const noticeTimers = new Map();
  const noticeDisplayMs = 1500;
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
    status: 'loaded'
  };
  let ingestResponse = null;
  let uploadResponse = null;
  let siteIngestResponse = null;
  let siteIngestStreamEvents = [];
  let siteIngestProgress = {
    phase: 'idle',
    message: '대기 중',
    current: 0,
    total: 0
  };
  let siteIngestStreamError = null;
  let siteIngestResultStatusFilter = 'all';
  let siteIngestResultSort = 'status';
  let searchResponse = null;
  let answerResponse = null;
  let answerStreamEvents = [];
  let selectedCitationIndex = null;
  let diagnoseResponse = null;
  let statsResponse = null;
  let healthResponse = null;
  let activeTab = 'search';
  let clearSampleConfirmOpen = false;
  const sampleDocPreviewLimit = 8;

  function toList(value) {
    return value
      .split(',')
      .map((item) => item.trim())
      .filter(Boolean);
  }

  function toMap(value) {
    return value
      .split('\n')
      .map((line) => line.trim())
      .filter(Boolean)
      .reduce((acc, line) => {
        const separatorIndex = line.indexOf('=');
        if (separatorIndex > 0 && separatorIndex < line.length - 1) {
          const key = line.slice(0, separatorIndex).trim();
          const mapValue = line.slice(separatorIndex + 1).trim();
          if (key && mapValue) {
            acc[key] = mapValue;
          }
        }
        return acc;
      }, {});
  }

  function toLines(value) {
    return value
      .split(/\r?\n/)
      .map((item) => item.trim())
      .filter(Boolean);
  }

  function parseEventData(value) {
    if (typeof value !== 'string') {
      return value;
    }

    try {
      return JSON.parse(value);
    } catch {
      return value;
    }
  }

  function parseIntegerInput(value, fallback, min = Number.NEGATIVE_INFINITY) {
    const parsed = Number(value);
    if (!Number.isInteger(parsed) || parsed < min) {
      return fallback;
    }
    return parsed;
  }

  function isIntegerInput(value, min = Number.NEGATIVE_INFINITY) {
    const parsed = Number(value);
    return value !== '' && Number.isInteger(parsed) && parsed >= min;
  }

  function isNonEmptyListInput(value) {
    return toLines(value).length > 0;
  }

  function pushNotice(kind, message, source = null) {
    const id = crypto.randomUUID();
    notifications = [{ id, kind, message, source }, ...notifications].slice(0, 4);

    const timer = setTimeout(() => dismissNotice(id), noticeDisplayMs);
    noticeTimers.set(id, timer);
  }

  function dismissNotice(id) {
    notifications = notifications.filter((notice) => notice.id !== id);
    const timer = noticeTimers.get(id);
    if (timer) {
      clearTimeout(timer);
      noticeTimers.delete(id);
    }
  }

  function setLoading(key, value) {
    loading = { ...loading, [key]: value };
  }

  function sampleDocPreview(docIds) {
    return docIds.slice(0, sampleDocPreviewLimit);
  }

  function selectTab(tab) {
    activeTab = tab;
  }

  function resolveNoticeSource(key) {
    return {
      sample: '/api/rag/demo/load-sample',
      ingest: '/api/rag/ingest',
      siteIngest: '/api/rag/site-ingest',
      upload: '/api/rag/ingest-file',
      search: '/api/rag/search',
      answer: '/api/rag/answer',
      answerStream: '/api/rag/answer/stream',
      diagnose: '/api/rag/diagnose-search',
      stats: '/api/rag/stats',
      health: '/api/rag/provider-health'
    }[key] ?? key;
  }

  function buildSearchPayload() {
    return {
      tenantId: searchForm.tenantId,
      principals: toList(searchForm.principals),
      query: searchForm.query,
      topK: parseIntegerInput(searchForm.topK, defaults.topK, 1),
      filter: toMap(searchForm.filter),
      providerHealthDetail: true,
      recentProviderWindowMillis: parseIntegerInput(searchForm.recentProviderWindowMillis, defaults.recentProviderWindowMillis, 0)
    };
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
      userAgent: siteIngestForm.userAgent.trim() || 'AinsoftRagBot/1.0',
      maxPages: parseIntegerInput(siteIngestForm.maxPages, 25, 1),
      maxDepth: parseIntegerInput(siteIngestForm.maxDepth, 1, 0),
      sameHostOnly: siteIngestForm.sameHostOnly,
      charset: siteIngestForm.charset.trim() || 'UTF-8'
    };
  }

  function splitSseBlock(block) {
    const lines = block
      .split(/\r?\n/)
      .map((line) => line.trimEnd())
      .filter(Boolean);
    const event = { event: 'message', data: [] };

    for (const line of lines) {
      if (line.startsWith('event:')) {
        event.event = line.slice(6).trim() || 'message';
      } else if (line.startsWith('data:')) {
        event.data.push(line.slice(5).trimStart());
      }
    }

    return event.data.length ? { event: event.event, data: event.data.join('\n') } : null;
  }

  function focusCitation(index) {
    selectedCitationIndex = index;
    const element = document.getElementById(`citation-${index}`);
    if (element) {
      element.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }
  }

  function recordSiteIngestEvent(event) {
    const parsed = parseEventData(event.data);
    const payload = parsed && typeof parsed === 'object' ? parsed : { message: String(parsed) };
    const entry = {
      id: crypto.randomUUID(),
      receivedAt: new Date().toISOString(),
      event: event.event,
      ...payload
    };

    siteIngestStreamEvents = [...siteIngestStreamEvents, entry].slice(-60);

    if (event.event === 'meta') {
      siteIngestProgress = {
        phase: 'starting',
        message: payload.message ?? '사이트 색인을 시작합니다.',
        current: 0,
        total: payload.urlCount ?? 0
      };
    } else if (event.event === 'progress') {
      siteIngestProgress = {
        phase: payload.phase ?? 'progress',
        message: payload.message ?? '진행 중',
        current: payload.current ?? siteIngestProgress.current,
        total: payload.total ?? siteIngestProgress.total
      };
    } else if (event.event === 'result') {
      siteIngestResponse = payload.response ?? payload;
      siteIngestProgress = {
        phase: 'done',
        message: `완료: ${siteIngestResponse.status ?? 'done'}`,
        current: siteIngestResponse.crawledPages ?? siteIngestProgress.current,
        total: siteIngestResponse.crawledPages ?? siteIngestProgress.total
      };
    } else if (event.event === 'done') {
      siteIngestProgress = {
        ...siteIngestProgress,
        phase: 'done',
        message: payload.status ? `완료: ${payload.status}` : '완료'
      };
    } else if (event.event === 'error') {
      siteIngestStreamError = payload.message ?? '사이트 색인 실패';
      siteIngestProgress = {
        ...siteIngestProgress,
        phase: 'error',
        message: siteIngestStreamError
      };
    }
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
    if (siteIngestResultSort === 'depth') {
      const depthDelta = (left.depth ?? Number.MAX_SAFE_INTEGER) - (right.depth ?? Number.MAX_SAFE_INTEGER);
      if (depthDelta !== 0) return depthDelta;
      return (left.title ?? left.docId).localeCompare(right.title ?? right.docId);
    }

    if (siteIngestResultSort === 'title') {
      return (left.title ?? left.docId).localeCompare(right.title ?? right.docId);
    }

    if (siteIngestResultSort === 'order') {
      return 0;
    }

    const statusDelta = rankSiteResultStatus(left.status) - rankSiteResultStatus(right.status);
    if (statusDelta !== 0) return statusDelta;
    const depthDelta = (left.depth ?? Number.MAX_SAFE_INTEGER) - (right.depth ?? Number.MAX_SAFE_INTEGER);
    if (depthDelta !== 0) return depthDelta;
    return (left.title ?? left.docId).localeCompare(right.title ?? right.docId);
  }

  function setSiteIngestResultFilter(value) {
    siteIngestResultStatusFilter = value;
  }

  function setSiteIngestResultSort(value) {
    siteIngestResultSort = value;
  }

  async function readSseResponse(response, onEvent) {
    const reader = response.body?.getReader();
    if (!reader) {
      throw new Error('Streaming response body is not available.');
    }

    const decoder = new TextDecoder();
    let buffer = '';
    let currentBlock = '';

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const chunks = buffer.split(/\r?\n/);
      buffer = chunks.pop() ?? '';

      for (const line of chunks) {
        if (line === '') {
          const parsed = splitSseBlock(currentBlock);
          if (parsed) onEvent(parsed);
          currentBlock = '';
        } else {
          currentBlock += `${line}\n`;
        }
      }
    }

    buffer += decoder.decode();
    const tail = [currentBlock, buffer].filter(Boolean).join('\n');
    const parsed = splitSseBlock(tail);
    if (parsed) onEvent(parsed);
  }

  async function withStatus(key, successMessage, job) {
    setLoading(key, true);
    try {
      const result = await job();
      if (successMessage) {
        pushNotice('success', successMessage, resolveNoticeSource(key));
      }
      return result;
    } catch (error) {
      pushNotice('error', error instanceof Error ? error.message : 'Unexpected error', resolveNoticeSource(key));
      throw error;
    } finally {
      setLoading(key, false);
    }
  }

  function applySamplePreset(response) {
    sampleResponse = response;
    statsTenantId = response.tenantId;
    ingestForm = {
      ...ingestForm,
      tenantId: response.tenantId,
      acl: response.principals.join(', ')
    };
    uploadForm = {
      ...uploadForm,
      tenantId: response.tenantId,
      acl: response.principals.join(', ')
    };
    searchForm = {
      ...searchForm,
      tenantId: response.tenantId,
      principals: response.principals.join(', '),
      query: response.suggestedQuery
    };
  }

  function applySampleClear(response) {
    sampleResponse = {
      tenantId: response.tenantId,
      principals: [],
      suggestedQuery: '하이브리드 검색',
      docIds: response.docIds ?? [],
      status: response.status
    };
    statsTenantId = response.tenantId;
    ingestForm = {
      ...ingestForm,
      tenantId: response.tenantId
    };
    uploadForm = {
      ...uploadForm,
      tenantId: response.tenantId
    };
    searchForm = {
      ...searchForm,
      tenantId: response.tenantId,
      principals: ''
    };
  }

  async function loadSampleData() {
    const response = await withStatus('sample', '샘플 데이터를 준비했습니다.', () =>
      apiPost('/api/rag/demo/load-sample', {})
    );
    applySamplePreset(response);
    await Promise.all([refreshStats(), refreshHealth()]);
    activeTab = 'search';
    await submitSearch();
  }

  async function checkSampleSync() {
    if (statsResponse && statsResponse.tenantId === sampleDefaults.tenantId) {
      if (statsResponse.docs === 0 && sampleResponse.docIds.length > 0) {
        applySampleClear({ tenantId: sampleDefaults.tenantId, status: 'cleared', docIds: [] });
      }
    }
  }

  async function clearSampleData() {
    const response = await withStatus('sample', '샘플 데이터를 삭제했습니다.', () =>
      apiPost('/api/rag/demo/clear-sample', {})
    );
    applySampleClear(response);
    siteIngestResponse = null;
    siteIngestStreamEvents = [];
    siteIngestStreamError = null;
    ingestResponse = null;
    uploadResponse = null;
    searchResponse = null;
    answerResponse = null;
    answerStreamEvents = [];
    diagnoseResponse = null;
    await Promise.all([refreshStats(), refreshHealth()]);
  }

  async function confirmClearSampleData() {
    clearSampleConfirmOpen = false;
    await clearSampleData();
  }

  async function submitIngest() {
    ingestResponse = await withStatus('ingest', '문서가 색인되었습니다.', () =>
      apiPost('/api/rag/ingest', {
        tenantId: ingestForm.tenantId,
        docId: ingestForm.docId,
        text: ingestForm.text,
        acl: toList(ingestForm.acl),
        metadata: toMap(ingestForm.metadata),
        sourceUri: ingestForm.sourceUri || null
      })
    );
    activeTab = 'ingest';
    statsTenantId = ingestForm.tenantId;
    searchForm = { ...searchForm, tenantId: ingestForm.tenantId };
    await refreshStats();
  }

  async function submitSiteIngest() {
    activeTab = 'ingest';
    siteIngestResponse = null;
    siteIngestStreamEvents = [];
    siteIngestStreamError = null;
    siteIngestProgress = {
      phase: 'starting',
      message: '사이트 색인을 시작합니다.',
      current: 0,
      total: 0
    };
    setLoading('siteIngest', true);
    try {
      const response = await fetch('/api/rag/site-ingest/stream', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify(buildSiteIngestPayload())
      });

      const contentType = response.headers.get('content-type') ?? '';
      if (!response.ok) {
        const payload = contentType.includes('application/json') ? await response.json() : await response.text();
        const message =
          (typeof payload === 'object' && payload !== null && 'message' in payload && payload.message) ||
          response.statusText ||
          'Request failed';
        throw new Error(String(message));
      }

      if (!contentType.includes('text/event-stream')) {
        throw new Error('Streaming response was not text/event-stream.');
      }

      await readSseResponse(response, recordSiteIngestEvent);
      if (siteIngestStreamError) {
        throw new Error(siteIngestStreamError);
      }
      if (siteIngestResponse?.status === 'failed') {
        throw new Error(siteIngestResponse.failures?.[0]?.message ?? '사이트 색인이 실패했습니다.');
      }
      pushNotice('success', '사이트가 색인되었습니다.', resolveNoticeSource('siteIngest'));
      statsTenantId = siteIngestForm.tenantId;
      searchForm = { ...searchForm, tenantId: siteIngestForm.tenantId };
      await refreshStats();
    } catch (error) {
      pushNotice('error', error instanceof Error ? error.message : 'Unexpected error', resolveNoticeSource('siteIngest'));
      throw error;
    } finally {
      setLoading('siteIngest', false);
    }
  }

  async function submitUpload() {
    if (!selectedFile) {
      pushNotice('error', '업로드할 파일을 선택해야 합니다.');
      return;
    }

    const formData = new FormData();
    formData.set('tenantId', uploadForm.tenantId);
    formData.set('docId', uploadForm.docId);
    toList(uploadForm.acl).forEach((item) => formData.append('acl', item));
    formData.set('metadata', uploadForm.metadata.replace(/\n/g, ','));
    if (uploadForm.sourceUri.trim()) {
      formData.set('sourceUri', uploadForm.sourceUri.trim());
    }
    formData.set('file', selectedFile);

    uploadResponse = await withStatus('upload', '파일이 업로드되어 색인되었습니다.', () =>
      apiUpload('/api/rag/ingest-file', formData)
    );
    activeTab = 'upload';
    statsTenantId = uploadForm.tenantId;
    searchForm = { ...searchForm, tenantId: uploadForm.tenantId };
    await refreshStats();
  }

  async function submitSearch() {
    activeTab = 'search';
    searchResponse = await withStatus('search', '검색 결과를 갱신했습니다.', () =>
      apiPost('/api/rag/search', {
        ...buildSearchPayload(),
        openSource: searchForm.openSource
      })
    );
  }

  async function submitAnswer() {
    activeTab = 'search';
    answerResponse = await withStatus('answer', '답변을 생성했습니다.', () =>
      apiPost('/api/rag/answer', buildSearchPayload())
    );
    selectedCitationIndex = null;
  }

  async function submitAnswerStream() {
    activeTab = 'search';
    setLoading('answerStream', true);
    answerStreamEvents = [];
    try {
      const response = await fetch('/api/rag/answer/stream', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify(buildSearchPayload())
      });

      const contentType = response.headers.get('content-type') ?? '';
      if (!response.ok) {
        const payload = contentType.includes('application/json') ? await response.json() : await response.text();
        const message =
          (typeof payload === 'object' && payload !== null && 'message' in payload && payload.message) ||
          response.statusText ||
          'Request failed';
        throw new Error(String(message));
      }

      if (!contentType.includes('text/event-stream')) {
        throw new Error('Streaming response was not text/event-stream.');
      }

      await readSseResponse(response, (event) => {
        answerStreamEvents = [...answerStreamEvents, { id: crypto.randomUUID(), ...event }];
      });
      pushNotice('success', '스트리밍 답변을 수신했습니다.');
    } catch (error) {
      pushNotice('error', error instanceof Error ? error.message : 'Unexpected error');
      throw error;
    } finally {
      setLoading('answerStream', false);
    }
  }

  async function runDiagnostics() {
    activeTab = 'search';
    diagnoseResponse = await withStatus('diagnose', '검색 진단 결과를 갱신했습니다.', () =>
      apiPost('/api/rag/diagnose-search', {
        tenantId: searchForm.tenantId,
        principals: toList(searchForm.principals),
        query: searchForm.query,
        topK: parsePositiveInt(searchForm.topK, defaults.topK),
        filter: toMap(searchForm.filter),
        providerHealthDetail: true,
        recentProviderWindowMillis: parsePositiveInt(
          searchForm.recentProviderWindowMillis,
          defaults.recentProviderWindowMillis
        )
      })
    );
  }

  $: searchFormValid =
    isIntegerInput(searchForm.topK, 1) && isIntegerInput(searchForm.recentProviderWindowMillis, 0);

  $: siteIngestValid =
    siteIngestForm.tenantId.trim().length > 0 &&
    isNonEmptyListInput(siteIngestForm.urls) &&
    isNonEmptyListInput(siteIngestForm.acl) &&
    isIntegerInput(siteIngestForm.maxPages, 1) &&
    isIntegerInput(siteIngestForm.maxDepth, 0);

  $: siteIngestVisibleResults = (siteIngestResponse?.results ?? [])
    .filter(
      (result) =>
        siteIngestResultStatusFilter === 'all' || result.status === siteIngestResultStatusFilter
    )
    .slice()
    .sort(compareSiteResults);

  async function refreshStats() {
    statsResponse = await withStatus('stats', '', () =>
      apiGet(
        `/api/rag/stats?tenantId=${encodeURIComponent(statsTenantId)}&recentProviderWindowMillis=${encodeURIComponent(searchForm.recentProviderWindowMillis)}`
      )
    );
  }

  async function refreshHealth() {
    healthResponse = await withStatus('health', '', () =>
      apiGet(
        `/api/rag/provider-health?recentProviderWindowMillis=${encodeURIComponent(searchForm.recentProviderWindowMillis)}&detailed=true`
      )
    );
  }

  onMount(async () => {
    await Promise.allSettled([refreshStats(), refreshHealth()]);
    await checkSampleSync();
    await Promise.allSettled([submitSearch()]);
  });

  onDestroy(() => {
    for (const timer of noticeTimers.values()) {
      clearTimeout(timer);
    }
    noticeTimers.clear();
  });

  const statCards = [
    {
      label: 'Indexed Docs',
      value: () => statsResponse?.docs ?? 0,
      helper: '현재 테넌트 기준 문서 수'
    },
    {
      label: 'Indexed Chunks',
      value: () => statsResponse?.chunks ?? 0,
      helper: '색인된 전체 청크 수'
    },
    {
      label: 'Provider Calls',
      value: () => statsResponse?.providerTelemetry?.requestCount ?? 0,
      helper: '누적 임베딩/리랭크 호출'
    },
    {
      label: 'Cache Hit Rate',
      value: () => `${statsResponse?.statsCacheHitRatePct ?? 0}%`,
      helper: '통계 캐시 적중률'
    }
  ];
</script>

<div class="shell" data-testid="demo-shell">
  <div class="ambient ambient-left"></div>
  <div class="ambient ambient-right"></div>

  <section class="hero">
    <div class="hero-copy">
      <p class="eyebrow">AINSOFT RAG SPRING BOOT DEMO</p>
      <h1>색인, 검색, 진단을 테스트하기 위한 데모사이트 입니다.</h1>
      <p class="lede">
        이 화면은 Spring Boot가 직접 제공하는 정적 파일입니다. 문서 색인, 파일 업로드, 검색,
        진단, 제공자 상태를 즉시 검증할 수 있습니다.
      </p>

      <div class="sample-banner" data-testid="sample-banner">
        <div>
          <span class="sample-label">Sample Tenant</span>
          <strong>{sampleResponse.tenantId}</strong>
        </div>
        <div>
          <span class="sample-label">Suggested Query</span>
          <strong>{sampleResponse.suggestedQuery}</strong>
        </div>
        <div class="button-row">
          <button
            data-testid="load-sample-button"
            disabled={loading.sample}
            on:click={loadSampleData}
          >
            {loading.sample ? '처리 중...' : 'Load Data'}
          </button>
          <button
            data-testid="clear-sample-button"
            disabled={loading.sample}
            on:click={() => (clearSampleConfirmOpen = true)}
          >
            {loading.sample ? '처리 중...' : 'Clear Data'}
          </button>
        </div>
      </div>

      {#if clearSampleConfirmOpen}
        <div class="sample-confirm" role="dialog" aria-modal="true" aria-label="Clear sample data confirmation">
          <div>
            <strong>샘플 데이터를 삭제할까요?</strong>
            <p>{sampleResponse.tenantId}에 적재된 샘플 문서 {sampleResponse.docIds.length}개를 모두 삭제합니다.</p>
          </div>
          <div class="button-row">
            <button class="ghost" type="button" on:click={() => (clearSampleConfirmOpen = false)}>
              Cancel
            </button>
            <button type="button" on:click={confirmClearSampleData}>Clear</button>
          </div>
        </div>
      {/if}
    </div>

    <div class="hero-stats-container">
      <div class="hero-stats">
        {#each statCards as card}
          <article class="stat-card" data-testid={`stat-${card.label.toLowerCase().replace(/\s+/g, '-')}`}>
            <span>{card.label}</span>
            <strong>{card.value()}</strong>
            <small>{card.helper}</small>
          </article>
        {/each}
      </div>

      {#if sampleResponse.docIds.length}
        <div class="hero-sample-docs">
          <p class="muted">샘플 문서 {sampleResponse.docIds.length}개가 준비되어 있습니다.</p>
          <div class="chip-field compact">
            {#each sampleDocPreview(sampleResponse.docIds) as docId}
              <span>{docId}</span>
            {/each}
            {#if sampleResponse.docIds.length > sampleDocPreviewLimit}
              <span>외 {sampleResponse.docIds.length - sampleDocPreviewLimit}개</span>
            {/if}
          </div>
        </div>
      {:else}
        <div class="hero-sample-docs">
          <p class="empty sample-empty">
            {sampleResponse.status === 'cleared' ? '샘플 데이터가 삭제되었습니다.' : '샘플 데이터가 비어 있습니다.'}
          </p>
        </div>
      {/if}
    </div>
  </section>

  {#if notifications.length}
    <section class="notices" data-testid="notifications" aria-live="polite" aria-atomic="true">
      {#each notifications as notice (notice.id)}
        <div class:success={notice.kind === 'success'} class:error={notice.kind === 'error'} class="notice">
          <span>{notice.kind === 'success' ? 'OK' : 'ERR'}</span>
          <div class="notice-copy">
            {#if notice.source}
              <small>{notice.source}</small>
            {/if}
            <p>{notice.message}</p>
          </div>
        </div>
      {/each}
    </section>
  {/if}

  <section class="workspace">
    <div class="workspace-tabs" role="tablist" aria-label="Demo panels">
      <button
        type="button"
        class:active={activeTab === 'search'}
        on:click={() => selectTab('search')}
      >
        검색과 진단
      </button>
      <button
        type="button"
        class:active={activeTab === 'ingest'}
        on:click={() => selectTab('ingest')}
      >
        텍스트 색인
      </button>
      <button
        type="button"
        class:active={activeTab === 'siteIngest'}
        on:click={() => selectTab('siteIngest')}
      >
        사이트 색인
      </button>
      <button
        type="button"
        class:active={activeTab === 'upload'}
        on:click={() => selectTab('upload')}
      >
        파일 업로드
      </button>
      <button
        type="button"
        class:active={activeTab === 'operations'}
        on:click={() => selectTab('operations')}
      >
        운영 상태
      </button>
    </div>

    {#if activeTab === 'ingest'}
      <div class="tab-layout">
        <div class="controls">
          <article class="panel">
            <div class="panel-header">
              <h2>텍스트 색인</h2>
              <button data-testid="ingest-submit" disabled={loading.ingest} on:click={submitIngest}>
                {loading.ingest ? '처리 중...' : 'JSON Ingest'}
              </button>
            </div>

            <div class="grid two">
              <label>
                <span>Tenant</span>
                <input bind:value={ingestForm.tenantId} data-testid="ingest-tenant" />
              </label>
              <label>
                <span>Doc ID</span>
                <input bind:value={ingestForm.docId} data-testid="ingest-doc-id" />
              </label>
            </div>

            <label>
              <span>ACL (comma separated)</span>
              <input bind:value={ingestForm.acl} />
            </label>

            <label>
              <span>Metadata (`key=value`, one per line)</span>
              <textarea bind:value={ingestForm.metadata} rows="3"></textarea>
            </label>

            <label>
              <span>Source URI</span>
              <input bind:value={ingestForm.sourceUri} placeholder="optional" />
            </label>

            <label>
              <span>Document Text</span>
              <textarea bind:value={ingestForm.text} data-testid="ingest-text" rows="12"></textarea>
            </label>
          </article>
        </div>

        <div class="results">
          <article class="panel result-panel">
            <div class="panel-header">
              <h2>최근 색인 응답</h2>
              <span class="pill">{ingestResponse?.status ?? 'idle'}</span>
            </div>

            {#if ingestResponse}
              <dl class="definition-list">
                <div>
                  <dt>Tenant</dt>
                  <dd>{ingestResponse.tenantId}</dd>
                </div>
                <div>
                  <dt>Doc ID</dt>
                  <dd>{ingestResponse.docId}</dd>
                </div>
              </dl>
            {:else}
              <p class="empty">아직 색인 요청이 없습니다.</p>
            {/if}
          </article>
        </div>
      </div>
    {:else if activeTab === 'siteIngest'}
      <div class="tab-layout">
        <div class="controls">
          <article class="panel">
            <div class="panel-header">
              <h2>사이트 색인</h2>
              <button
                data-testid="site-ingest-submit"
                disabled={loading.siteIngest || !siteIngestValid}
                on:click={submitSiteIngest}
              >
                {loading.siteIngest ? '색인 중...' : 'Site Ingest'}
              </button>
            </div>

            <div class="grid two">
              <label>
                <span>Tenant</span>
                <input bind:value={siteIngestForm.tenantId} data-testid="site-ingest-tenant" />
              </label>
              <label>
                <span>Max Pages</span>
                <input bind:value={siteIngestForm.maxPages} min="1" type="number" />
              </label>
            </div>

            <div class="grid two">
              <label>
                <span>Max Depth</span>
                <input bind:value={siteIngestForm.maxDepth} min="0" type="number" />
              </label>
              <label>
                <span>Charset</span>
                <input bind:value={siteIngestForm.charset} />
              </label>
            </div>

            <label>
              <span>Seed URLs (`URL` per line)</span>
              <textarea bind:value={siteIngestForm.urls} rows="3"></textarea>
            </label>

            <label>
              <span>Allowed Domains (`domain` per line)</span>
              <textarea bind:value={siteIngestForm.allowedDomains} rows="3"></textarea>
            </label>

            <label>
              <span>ACL (`principal` per line)</span>
              <textarea bind:value={siteIngestForm.acl} rows="3"></textarea>
            </label>

            <label>
              <span>Metadata (`key=value`, one per line)</span>
              <textarea bind:value={siteIngestForm.metadata} rows="3"></textarea>
            </label>

            <div class="grid two compact">
              <label class="checkbox">
                <span>Same Host Only</span>
                <input bind:checked={siteIngestForm.sameHostOnly} type="checkbox" />
              </label>

              <label class="checkbox">
                <span>Respect robots.txt</span>
                <input bind:checked={siteIngestForm.respectRobotsTxt} type="checkbox" />
              </label>
            </div>

            <div class="grid two compact">
              <label class="checkbox">
                <span>Incremental Ingest</span>
                <input bind:checked={siteIngestForm.incrementalIngest} type="checkbox" />
              </label>

              <label>
                <span>User Agent</span>
                <input bind:value={siteIngestForm.userAgent} />
              </label>
            </div>
          </article>
        </div>

        <div class="results">
          <article class="panel result-panel">
            <div class="panel-header">
              <h2>사이트 색인 결과</h2>
              <span class="pill">{siteIngestResponse?.status ?? 'idle'}</span>
            </div>

            {#if siteIngestResponse}
              <div class="tag-row filter-row">
                <button
                  type="button"
                  class:active={siteIngestResultStatusFilter === 'all'}
                  on:click={() => setSiteIngestResultFilter('all')}
                >
                  All
                </button>
                <button
                  type="button"
                  class:active={siteIngestResultStatusFilter === 'changed'}
                  on:click={() => setSiteIngestResultFilter('changed')}
                >
                  Changed
                </button>
                <button
                  type="button"
                  class:active={siteIngestResultStatusFilter === 'ingested'}
                  on:click={() => setSiteIngestResultFilter('ingested')}
                >
                  Ingested
                </button>
                <button
                  type="button"
                  class:active={siteIngestResultStatusFilter === 'skipped'}
                  on:click={() => setSiteIngestResultFilter('skipped')}
                >
                  Skipped
                </button>
                <button
                  type="button"
                  class:active={siteIngestResultStatusFilter === 'failed'}
                  on:click={() => setSiteIngestResultFilter('failed')}
                >
                  Failed
                </button>
              </div>

              <div class="tag-row filter-row">
                <button
                  type="button"
                  class:active={siteIngestResultSort === 'status'}
                  on:click={() => setSiteIngestResultSort('status')}
                >
                  Status
                </button>
                <button
                  type="button"
                  class:active={siteIngestResultSort === 'depth'}
                  on:click={() => setSiteIngestResultSort('depth')}
                >
                  Depth
                </button>
                <button
                  type="button"
                  class:active={siteIngestResultSort === 'title'}
                  on:click={() => setSiteIngestResultSort('title')}
                >
                  Title
                </button>
                <button
                  type="button"
                  class:active={siteIngestResultSort === 'order'}
                  on:click={() => setSiteIngestResultSort('order')}
                >
                  Crawl Order
                </button>
              </div>

              <div class="metric-list">
                <div>
                  <span>Crawled</span>
                  <strong>{siteIngestResponse.crawledPages}</strong>
                </div>
                <div>
                  <span>Ingested</span>
                  <strong>{siteIngestResponse.ingestedPages}</strong>
                </div>
                <div>
                  <span>Changed</span>
                  <strong>{siteIngestResponse.changedPages}</strong>
                </div>
                <div>
                  <span>Skipped</span>
                  <strong>{siteIngestResponse.skippedPages}</strong>
                </div>
              </div>

              {#if siteIngestVisibleResults.length}
                <div class="result-card-grid">
                  {#each siteIngestVisibleResults as result, index}
                    <article class="result-card">
                      <div class="hit-topline">
                        <strong>{index + 1}. {result.title ?? result.docId}</strong>
                        <span>{result.status}</span>
                      </div>
                      <p class="muted">{result.url}</p>
                      <div class="tag-row">
                        <span>{result.docId}</span>
                        <span>depth {result.depth}</span>
                        <span>{result.source}</span>
                        {#if result.message}
                          <span>{result.message}</span>
                        {/if}
                        {#if result.changeSummary}
                          <span>{result.changeSummary}</span>
                        {/if}
                      </div>
                      {#if result.previousPreview || result.currentPreview}
                        <details class="debug-json">
                          <summary>Preview Diff</summary>
                          <div class="preview-grid">
                            <article>
                              <span>Previous</span>
                              <pre>{result.previousPreview ?? 'n/a'}</pre>
                            </article>
                            <article>
                              <span>Current</span>
                              <pre>{result.currentPreview ?? 'n/a'}</pre>
                            </article>
                          </div>
                        </details>
                      {/if}
                    </article>
                  {/each}
                </div>
              {:else}
                <p class="empty">선택한 필터에 맞는 결과가 없습니다.</p>
              {/if}

              {#if siteIngestResponse.failures?.length}
                <details class="debug-json">
                  <summary>Failures ({siteIngestResponse.failures.length})</summary>
                  <div class="stream-list">
                    {#each siteIngestResponse.failures as failure}
                      <article class="stream-event">
                        <div class="hit-topline">
                          <strong>{failure.depth}d</strong>
                          <span>{failure.url}</span>
                        </div>
                        <pre>{failure.message}</pre>
                      </article>
                    {/each}
                  </div>
                </details>
              {/if}
            {:else}
              <p class="empty">아직 사이트 색인 요청이 없습니다.</p>
            {/if}
          </article>

          <details class="panel result-panel debug-panel" open={siteIngestStreamEvents.length > 0}>
            <summary class="panel-header">
              <h2>Site Ingest Timeline</h2>
              <span class="pill">{siteIngestProgress.phase}</span>
            </summary>

            <div class="metric-list compact-summary">
              <div>
                <span>Progress</span>
                <strong>{siteIngestProgress.current} / {siteIngestProgress.total}</strong>
              </div>
              <div>
                <span>Message</span>
                <strong>{siteIngestProgress.message}</strong>
              </div>
              <div>
                <span>Events</span>
                <strong>{siteIngestStreamEvents.length}</strong>
              </div>
              <div>
                <span>Tenant</span>
                <strong>{siteIngestForm.tenantId}</strong>
              </div>
            </div>

            {#if siteIngestStreamEvents.length}
              <div class="stream-list">
                {#each [...siteIngestStreamEvents].reverse().slice(0, 8) as event}
                  <article class="stream-event">
                    <div class="hit-topline">
                      <strong>{event.event}</strong>
                      <span>{event.phase ?? 'n/a'}</span>
                    </div>
                    <p>{event.message ?? event.status ?? 'no message'}</p>
                    {#if event.url}
                      <div class="tag-row">
                        <span>{event.url}</span>
                        {#if event.depth !== null && event.depth !== undefined}
                          <span>depth {event.depth}</span>
                        {/if}
                        {#if event.current !== null && event.current !== undefined && event.total !== null && event.total !== undefined}
                          <span>{event.current}/{event.total}</span>
                        {/if}
                      </div>
                    {/if}
                  </article>
                {/each}
              </div>
            {:else}
              <p class="empty">사이트 색인을 실행하면 진행 이벤트가 여기에 표시됩니다.</p>
            {/if}

            <details class="debug-json">
              <summary>Outgoing JSON</summary>
              <pre>{JSON.stringify(buildSiteIngestPayload(), null, 2)}</pre>
            </details>
          </details>
        </div>
      </div>
    {:else if activeTab === 'upload'}
      <div class="tab-layout">
        <div class="controls">
          <article class="panel">
            <div class="panel-header">
              <h2>파일 업로드</h2>
              <button data-testid="upload-submit" disabled={loading.upload} on:click={submitUpload}>
                {loading.upload ? '처리 중...' : 'Multipart Upload'}
              </button>
            </div>

            <div class="grid two">
              <label>
                <span>Tenant</span>
                <input bind:value={uploadForm.tenantId} data-testid="upload-tenant" />
              </label>
              <label>
                <span>Doc ID</span>
                <input bind:value={uploadForm.docId} data-testid="upload-doc-id" />
              </label>
            </div>

            <label>
              <span>ACL</span>
              <input bind:value={uploadForm.acl} />
            </label>

            <label>
              <span>Metadata</span>
              <textarea bind:value={uploadForm.metadata} rows="3"></textarea>
            </label>

            <label>
              <span>Source URI</span>
              <input bind:value={uploadForm.sourceUri} placeholder="optional" />
            </label>

            <label class="file-picker">
              <span>File</span>
              <input
                data-testid="upload-file-input"
                type="file"
                on:change={(event) => {
                  selectedFile = event.currentTarget.files?.[0] ?? null;
                }}
              />
              <small data-testid="upload-file-name">{selectedFile ? selectedFile.name : '선택된 파일 없음'}</small>
            </label>
          </article>
        </div>

        <div class="results">
          <article class="panel result-panel">
            <div class="panel-header">
              <h2>최근 파일 업로드 응답</h2>
              <span class="pill">{uploadResponse?.status ?? 'idle'}</span>
            </div>

            {#if uploadResponse}
              <div class="result-card-grid">
                <article class="result-card">
                  <div class="hit-topline">
                    <strong>{uploadResponse.fileName ?? selectedFile?.name ?? uploadResponse.docId}</strong>
                    <span class={`status-badge status-${uploadResponse.status}`}>{uploadResponse.status}</span>
                  </div>
                  <p class="muted">{uploadResponse.message ?? '업로드 결과'}</p>
                  <div class="tag-row">
                    <span>{uploadResponse.tenantId}</span>
                    <span>{uploadResponse.docId}</span>
                    {#if uploadResponse.page !== null && uploadResponse.page !== undefined}
                      <span>page {uploadResponse.page}</span>
                    {/if}
                    {#if uploadResponse.contentType}
                      <span>{uploadResponse.contentType}</span>
                    {/if}
                  </div>
                </article>

                <article class="result-card">
                  <div class="hit-topline">
                    <strong>Source & Change</strong>
                    <span>{uploadResponse.changeSummary ?? 'none'}</span>
                  </div>
                  <p class="muted">{uploadResponse.sourceUri ?? 'n/a'}</p>
                  {#if uploadResponse.previousPreview || uploadResponse.currentPreview}
                    <details class="debug-json">
                      <summary>Preview Diff</summary>
                      <div class="preview-grid">
                        <article>
                          <span>Previous</span>
                          <pre>{uploadResponse.previousPreview ?? 'n/a'}</pre>
                        </article>
                        <article>
                          <span>Current</span>
                          <pre>{uploadResponse.currentPreview ?? 'n/a'}</pre>
                        </article>
                      </div>
                    </details>
                  {/if}
                </article>
              </div>

              {#if Object.keys(uploadResponse.metadata ?? {}).length}
                <div class="chip-field">
                  {#each Object.entries(uploadResponse.metadata) as [key, value]}
                    <span>{key}: {value}</span>
                  {/each}
                </div>
              {/if}
            {:else}
              <p class="empty">아직 업로드 요청이 없습니다.</p>
            {/if}
          </article>
        </div>
      </div>
    {:else if activeTab === 'search'}
      <div class="tab-layout search-layout">
        <div class="controls">
          <article class="panel">
            <div class="panel-header">
              <h2>검색과 진단</h2>
            </div>

            <div class="grid four search-actions">
              <button
                data-testid="search-submit"
                disabled={loading.search || !searchFormValid}
                on:click={submitSearch}
              >
                {loading.search ? '검색 중...' : 'Search'}
              </button>
              <button
                data-testid="answer-submit"
                disabled={loading.answer || !searchFormValid}
                on:click={submitAnswer}
              >
                {loading.answer ? '생성 중...' : 'Answer'}
              </button>
              <button
                class="ghost"
                data-testid="answer-stream-submit"
                disabled={loading.answerStream || !searchFormValid}
                on:click={submitAnswerStream}
              >
                {loading.answerStream ? '스트리밍 중...' : 'Stream'}
              </button>
              <button
                class="ghost"
                data-testid="diagnose-submit"
                disabled={loading.diagnose || !searchFormValid}
                on:click={runDiagnostics}
              >
                {loading.diagnose ? '진단 중...' : 'Diagnose'}
              </button>
            </div>

            <div class="grid two">
              <label>
                <span>Tenant</span>
                <input bind:value={searchForm.tenantId} data-testid="search-tenant" />
              </label>
              <label>
                <span>Top K</span>
                <input bind:value={searchForm.topK} min="1" max="20" required type="number" />
              </label>
            </div>

            <label>
              <span>Principals</span>
              <input bind:value={searchForm.principals} data-testid="search-principals" />
            </label>

            <label>
              <span>Query</span>
              <input bind:value={searchForm.query} data-testid="search-query" />
            </label>

            <label>
              <span>Metadata Filter (`key=value`, one per line)</span>
              <textarea bind:value={searchForm.filter} rows="3"></textarea>
            </label>

            <div class="grid two compact">
              <label>
                <span>Provider Window (ms)</span>
                <input bind:value={searchForm.recentProviderWindowMillis} required type="number" min="0" />
              </label>

              <label class="checkbox">
                <span>Open Source Snippet</span>
                <input bind:checked={searchForm.openSource} type="checkbox" />
              </label>
            </div>
          </article>
        </div>

        <div class="results">
          <article class="panel result-panel" data-testid="answer-panel">
            <div class="panel-header">
              <h2>답변</h2>
              <span class="pill">{answerResponse?.schemaVersion ?? 'idle'}</span>
            </div>

            {#if answerResponse}
              <section class="answer-block">
                <p class="answer-lead">{answerResponse.answer.text}</p>

                {#if answerResponse.answer.sentences?.length}
                  <div class="sentence-list">
                    {#each answerResponse.answer.sentences as sentence}
                      <article class="sentence-card">
                        <div class="hit-topline">
                          <strong>Sentence {sentence.index}</strong>
                          {#if sentence.citationIndexes.length}
                            <div class="citation-pills">
                              {#each sentence.citationIndexes as citationIndex}
                                <button
                                  type="button"
                                  class="citation-pill"
                                  on:click={() => focusCitation(citationIndex)}
                                >
                                  [{citationIndex}]
                                </button>
                              {/each}
                            </div>
                          {:else}
                            <span>uncited</span>
                          {/if}
                        </div>
                        <p>{sentence.text}</p>
                      </article>
                    {/each}
                  </div>
                {/if}

                {#if answerResponse.citations?.length}
                  <div class="citation-list">
                    {#each answerResponse.citations as citation}
                      <article
                        class:selected={selectedCitationIndex === citation.index}
                        class="citation-card"
                        id={`citation-${citation.index}`}
                      >
                        <div class="hit-topline">
                          <strong>[{citation.index}] {citation.docId}</strong>
                          <span>{citation.score.toFixed(4)}</span>
                        </div>
                        <p>{citation.sourceSnippet ?? 'snippet unavailable'}</p>
                        <div class="tag-row">
                          <span>{citation.chunkId}</span>
                          {#if citation.sourceUri}
                            <span>{citation.sourceUri}</span>
                          {/if}
                          <span>{citation.contentKind}</span>
                        </div>
                      </article>
                    {/each}
                  </div>
                {/if}

                <div class="metric-list answer-meta">
                  <div>
                    <span>Mode</span>
                    <strong>{answerResponse.meta.answerMode}</strong>
                  </div>
                  <div>
                    <span>Selected</span>
                    <strong>{answerResponse.meta.selectedCount}</strong>
                  </div>
                  <div>
                    <span>Fallback</span>
                    <strong>{answerResponse.meta.fallbackApplied ? 'yes' : 'no'}</strong>
                  </div>
                  <div>
                    <span>Model</span>
                    <strong>{answerResponse.meta.usedModel ?? 'none'}</strong>
                  </div>
                </div>
              </section>
            {:else}
              <p class="empty">Answer 버튼을 누르면 구조화된 답변이 표시됩니다.</p>
            {/if}
          </article>

          <article class="panel result-panel" data-testid="answer-stream-panel">
            <div class="panel-header">
              <h2>Answer Stream</h2>
              <span class="pill">{answerStreamEvents.length} events</span>
            </div>

            {#if answerStreamEvents.length}
              <div class="stream-list">
                {#each answerStreamEvents as event}
                  <article class="stream-event">
                    <div class="hit-topline">
                      <strong>{event.event}</strong>
                    </div>
                    <pre>{event.data}</pre>
                  </article>
                {/each}
              </div>
            {:else}
              <p class="empty">Stream 버튼을 누르면 SSE 이벤트가 여기에 표시됩니다.</p>
            {/if}
          </article>

          <article class="panel result-panel" data-testid="search-results-panel">
            <div class="panel-header">
              <h2>검색 결과</h2>
              <span class="pill">{searchResponse?.meta?.resultCount ?? 0} hits</span>
            </div>

            {#if searchResponse?.hits?.length}
              <div class="hit-list" data-testid="search-results">
                {#each searchResponse.hits as hit}
                  <article class="hit-card" data-testid="search-hit">
                    <div class="hit-topline">
                      <strong>{hit.docId}</strong>
                      <span>{hit.score.toFixed(4)}</span>
                    </div>
                    <p>{hit.sourceSnippet ?? hit.text ?? '본문이 저장되지 않았습니다.'}</p>
                    <div class="tag-row">
                      <span>{hit.contentKind}</span>
                      {#if hit.page !== null}
                        <span>page {hit.page}</span>
                      {/if}
                      {#each Object.entries(hit.metadata) as [key, value]}
                        <span>{key}: {value}</span>
                      {/each}
                    </div>
                  </article>
                {/each}
              </div>
            {:else if searchResponse}
              <p class="empty">검색 결과가 없습니다. emptyReason: {searchResponse.meta.emptyReason ?? 'none'}</p>
            {:else}
              <p class="empty">검색을 실행하면 결과가 여기에 표시됩니다.</p>
            {/if}
          </article>

          <article class="panel result-panel">
            <div class="panel-header">
              <h2>검색 진단</h2>
              <span class="pill">{diagnoseResponse?.derivedEmptyReason ?? 'idle'}</span>
            </div>

            {#if diagnoseResponse}
              <div class="diagnostic-grid">
                <div>
                  <span>Tenant Docs</span>
                  <strong>{diagnoseResponse.tenantDocs}</strong>
                </div>
                <div>
                  <span>Lexical / ACL</span>
                  <strong>{diagnoseResponse.lexicalMatchesWithAcl}</strong>
                </div>
                <div>
                  <span>Vector / ACL</span>
                  <strong>{diagnoseResponse.vectorMatchesWithAcl}</strong>
                </div>
                <div>
                  <span>Providers Used</span>
                  <strong>{diagnoseResponse.providersUsed.join(', ') || 'none'}</strong>
                </div>
              </div>

              <div class="chip-field">
                {#each diagnoseResponse.notes as note}
                  <span>{note}</span>
                {/each}
              </div>
            {:else}
              <p class="empty">진단 버튼을 누르면 검색 경로 분석이 표시됩니다.</p>
            {/if}
          </article>
        </div>
      </div>
    {:else if activeTab === 'operations'}
      <div class="tab-layout">
        <div class="controls">
          <article class="panel">
            <div class="panel-header">
              <h2>운영 상태</h2>
              <div class="button-row">
                <button disabled={loading.stats} on:click={refreshStats}>
                  {loading.stats ? '새로고침 중...' : 'Stats'}
                </button>
                <button class="ghost" disabled={loading.health} on:click={refreshHealth}>
                  {loading.health ? '새로고침 중...' : 'Provider'}
                </button>
              </div>
            </div>

            <label>
              <span>Stats Tenant</span>
              <input bind:value={statsTenantId} />
            </label>

            <div class="metric-list">
              <div>
                <span>Snapshots</span>
                <strong>{statsResponse?.snapshotCount ?? 0}</strong>
              </div>
              <div>
                <span>Index Size</span>
                <strong>{statsResponse?.indexSizeBytes ?? 0} B</strong>
              </div>
              <div>
                <span>Recent Window</span>
                <strong>{healthResponse?.recentProviderWindowMillis ?? 0} ms</strong>
              </div>
              <div>
                <span>Fallback Used</span>
                <strong>{searchResponse?.meta?.providerFallbackApplied ? 'yes' : 'no'}</strong>
              </div>
            </div>
          </article>
        </div>

        <div class="results">
          <article class="panel result-panel compact-summary">
            <div class="panel-header">
              <h2>운영 요약</h2>
              <span class="pill">{statsResponse?.tenantId ?? statsTenantId}</span>
            </div>

            {#if healthResponse}
              <div class="metric-list">
                <div>
                  <span>Docs / Chunks</span>
                  <strong>{statsResponse?.docs ?? 0} / {statsResponse?.chunks ?? 0}</strong>
                </div>
                <div>
                  <span>Provider Calls</span>
                  <strong>{healthResponse.providerTelemetry.requestCount}</strong>
                </div>
                <div>
                  <span>Success Rate</span>
                  <strong>{healthResponse.providerTelemetry.successRatePct ?? 0}%</strong>
                </div>
                <div>
                  <span>Avg Latency</span>
                  <strong>{healthResponse.providerTelemetry.avgLatencyMillis ?? 0} ms</strong>
                </div>
              </div>

              <div class="chip-field">
                <span>Recent {healthResponse.recentProviderTelemetry?.requestCount ?? 0} req</span>
                <span>{searchResponse?.meta?.providerFallbackApplied ? 'fallback used' : 'fallback not used'}</span>
                <span>{searchResponse?.meta?.rerankerType ?? 'reranker none'}</span>
              </div>
            {:else}
              <p class="empty">Provider 상태를 불러오면 호출량과 성공률이 표시됩니다.</p>
            {/if}
          </article>
        </div>
      </div>
    {/if}
  </section>
</div>

<style>
  :global(body) {
    margin: 0;
    min-height: 100vh;
    font-family: 'Avenir Next', 'Segoe UI', sans-serif;
    background:
      radial-gradient(circle at top left, rgba(255, 210, 142, 0.24), transparent 28%),
      radial-gradient(circle at 80% 20%, rgba(89, 164, 255, 0.2), transparent 20%),
      linear-gradient(180deg, #07121b 0%, #0f2031 48%, #efe4d2 48%, #f5efe6 100%);
    color: #0f1720;
  }

  .shell {
    position: relative;
    overflow: hidden;
    padding: 40px 28px 56px;
  }

  .ambient {
    position: absolute;
    border-radius: 999px;
    filter: blur(16px);
    opacity: 0.8;
    pointer-events: none;
  }

  .ambient-left {
    top: 120px;
    left: -60px;
    width: 180px;
    height: 180px;
    background: rgba(255, 174, 77, 0.26);
  }

  .ambient-right {
    top: 420px;
    right: -40px;
    width: 220px;
    height: 220px;
    background: rgba(67, 142, 255, 0.18);
  }

  .hero,
  .workspace,
  .notices-shell {
    position: relative;
    z-index: 1;
    max-width: 1152px;
    margin: 0 auto;
  }

  .hero {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 12px;
    margin-bottom: 12px;
    align-items: stretch;
    animation: rise 500ms ease-out both;
  }

  .hero-copy,
  .stat-card,
  .panel,
  .notice {
    border: 1px solid rgba(255, 255, 255, 0.12);
    box-shadow: 0 16px 48px rgba(5, 16, 25, 0.08);
    backdrop-filter: blur(14px);
  }

  .hero-copy {
    padding: 14px 16px;
    border-radius: 20px;
    background: rgba(7, 18, 27, 0.76);
    color: #f8f1e7;
    display: flex;
    flex-direction: column;
    justify-content: center;
  }

  .eyebrow {
    margin: 0 0 6px;
    color: #f9c76d;
    font-size: 0.68rem;
    letter-spacing: 0.16em;
  }

  h1,
  h2,
  strong {
    font-family: Georgia, 'Times New Roman', serif;
  }

  h1 {
    margin: 0;
    font-size: clamp(1.2rem, 2.4vw, 1.8rem);
    line-height: 1.1;
  }

  .lede {
    max-width: 62ch;
    margin: 4px 0 0;
    font-size: 0.84rem;
    color: rgba(248, 241, 231, 0.82);
    line-height: 1.4;
  }

  .sample-banner {
    display: grid;
    grid-template-columns: 1fr 1fr auto;
    gap: 8px;
    align-items: center;
    margin-top: 8px;
    padding: 8px 10px;
    border-radius: 14px;
    background: rgba(248, 241, 231, 0.06);
  }

  .sample-banner .button-row {
    display: flex;
    gap: 6px;
    flex-shrink: 0;
  }

  .sample-banner button {
    white-space: nowrap;
    padding: 8px 12px;
    font-size: 0.84rem;
  }

  .sample-confirm {
    display: grid;
    gap: 10px;
    margin-top: 8px;
    padding: 12px 14px;
    border-radius: 14px;
    background: rgba(255, 214, 214, 0.14);
    border: 1px solid rgba(255, 214, 214, 0.24);
  }

  .sample-confirm p {
    margin: 2px 0 0;
    color: rgba(248, 241, 231, 0.78);
  }

  .sample-label {
    display: block;
    color: rgba(248, 241, 231, 0.68);
    font-size: 0.64rem;
    letter-spacing: 0.04em;
    text-transform: uppercase;
  }

  .sample-banner strong {
    display: block;
    margin-top: 2px;
    font-size: 0.88rem;
  }

  .hero-stats-container {
    display: flex;
    flex-direction: column;
    gap: 8px;
    align-self: stretch;
    justify-content: center;
  }

  .hero-stats {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 6px;
    align-content: stretch;
    flex-shrink: 0;
  }

  .hero-sample-docs {
    padding: 10px 12px;
    border-radius: 18px;
    background: rgba(7, 18, 27, 0.42);
    border: 1px solid rgba(255, 255, 255, 0.08);
  }

  .hero-sample-docs .muted {
    margin: 0 0 6px;
    color: rgba(248, 241, 231, 0.62);
    font-size: 0.72rem;
  }

  .stat-card {
    padding: 10px 8px;
    border-radius: 16px;
    background: rgba(248, 241, 231, 0.84);
    animation: rise 650ms ease-out both;
    display: flex;
    flex-direction: column;
    justify-content: center;
    align-items: center;
    text-align: center;
    min-height: 0;
  }

  .stat-card span,
  .panel label span,
  .metric-list span,
  .definition-list dt {
    display: block;
    color: #51606d;
    font-size: 0.64rem;
    letter-spacing: 0.02em;
    text-transform: uppercase;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    width: 100%;
  }

  .stat-card strong {
    display: block;
    margin: 2px 0;
    font-size: 1.25rem;
    color: #0f1720;
    line-height: 1;
  }

  .stat-card small {
    display: none;
  }

  .notices {
    position: fixed;
    top: 18px;
    right: 18px;
    z-index: 50;
    display: grid;
    gap: 10px;
    width: min(420px, calc(100vw - 36px));
    pointer-events: none;
  }

  .notice {
    display: grid;
    grid-template-columns: 48px 1fr;
    align-items: center;
    gap: 12px;
    padding: 14px 16px;
    border-radius: 18px;
    background: rgba(248, 241, 231, 0.84);
    pointer-events: auto;
  }

  .notice-copy {
    display: grid;
    gap: 4px;
  }

  .notice-copy small {
    color: #51606d;
    font-size: 0.72rem;
    letter-spacing: 0.08em;
    text-transform: uppercase;
  }

  .notice.success span,
  .pill {
    background: #112e24;
    color: #9af0b4;
  }

  .notice.error span {
    background: #4b1616;
    color: #ffb0b0;
  }

  .notice span,
  .pill {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    min-height: 32px;
    padding: 0 12px;
    border-radius: 999px;
    font-size: 0.78rem;
    letter-spacing: 0.08em;
    text-transform: uppercase;
  }

  .workspace {
    display: grid;
    gap: 18px;
  }

  .workspace-tabs {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    padding: 12px;
    border-radius: 22px;
    background: rgba(248, 241, 231, 0.72);
    border: 1px solid rgba(17, 27, 38, 0.08);
    box-shadow: 0 18px 50px rgba(5, 16, 25, 0.08);
    backdrop-filter: blur(14px);
  }

  .workspace-tabs button {
    border: 1px solid rgba(17, 27, 38, 0.08);
    background: rgba(255, 255, 255, 0.64);
    color: #10202f;
  }

  .workspace-tabs button.active {
    background: linear-gradient(135deg, #111b26 0%, #1d5c95 100%);
    color: #f7efe3;
  }

  .tab-layout {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 22px;
    align-items: stretch;
  }

  .tab-layout.search-layout {
    grid-template-columns: minmax(0, 1.8fr) minmax(0, 2.2fr);
  }

  .controls,
  .results {
    display: grid;
    gap: 18px;
    align-content: start;
  }

  .controls > .panel,
  .results > .panel {
    height: 100%;
  }

  .panel {
    padding: 22px;
    border-radius: 24px;
    background: rgba(248, 241, 231, 0.84);
    animation: rise 700ms ease-out both;
  }

  .panel-header,
  .button-row,
  .hit-topline {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
  }

  .panel-header {
    margin-bottom: 16px;
  }

  details > summary.panel-header {
    list-style: none;
    cursor: pointer;
  }

  details > summary.panel-header::-webkit-details-marker {
    display: none;
  }

  .panel-header h2 {
    margin: 0;
    font-size: 1.4rem;
  }

  .grid {
    display: grid;
    gap: 14px;
  }

  .grid.two {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .grid.four {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }

  .search-actions {
    margin-bottom: 20px;
  }

  .search-actions button {
    padding: 10px 8px;
    font-size: 0.88rem;
  }

  .grid.compact {
    align-items: end;
  }

  label {
    display: grid;
    gap: 8px;
    margin-bottom: 14px;
  }

  input,
  textarea,
  button {
    font: inherit;
  }

  input,
  textarea {
    width: 100%;
    box-sizing: border-box;
    border: 1px solid rgba(23, 36, 48, 0.12);
    border-radius: 16px;
    background: rgba(255, 255, 255, 0.72);
    padding: 13px 14px;
    color: #0f1720;
    transition: border-color 180ms ease, transform 180ms ease, box-shadow 180ms ease;
  }

  input:focus,
  textarea:focus {
    outline: none;
    border-color: rgba(24, 105, 192, 0.62);
    box-shadow: 0 0 0 4px rgba(24, 105, 192, 0.1);
    transform: translateY(-1px);
  }

  textarea {
    resize: vertical;
  }

  button {
    border: none;
    border-radius: 999px;
    padding: 12px 18px;
    cursor: pointer;
    background: linear-gradient(135deg, #111b26 0%, #1d5c95 100%);
    color: #f7efe3;
    font-weight: 600;
    transition: transform 180ms ease, opacity 180ms ease, box-shadow 180ms ease;
  }

  button.ghost {
    background: rgba(17, 27, 38, 0.08);
    color: #10202f;
  }

  button:hover:enabled {
    transform: translateY(-1px);
    box-shadow: 0 14px 30px rgba(17, 27, 38, 0.16);
  }

  button:disabled {
    cursor: wait;
    opacity: 0.6;
  }

  .checkbox {
    display: flex;
    flex-direction: column;
    justify-content: end;
  }

  .checkbox input {
    width: 18px;
    height: 18px;
    margin: 0;
  }

  .file-picker small,
  .empty {
    color: #5f6e79;
  }

  .result-panel {
    min-height: 160px;
  }

  .compact-summary {
    min-height: 0;
  }

  .debug-panel {
    min-height: 0;
  }

  .debug-json {
    display: grid;
    gap: 12px;
  }

  .debug-json summary {
    cursor: pointer;
    color: #10202f;
    font-weight: 600;
  }

  .debug-json pre {
    margin: 0;
    padding: 14px 16px;
    border-radius: 18px;
    background: rgba(17, 27, 38, 0.06);
    overflow: auto;
    font-family: 'SFMono-Regular', Consolas, monospace;
    font-size: 0.8rem;
    line-height: 1.5;
  }

  .definition-list,
  .metric-list,
  .diagnostic-grid {
    display: grid;
    gap: 14px;
  }

  .definition-list div,
  .metric-list div,
  .diagnostic-grid div {
    padding: 14px 16px;
    border-radius: 16px;
    background: rgba(255, 255, 255, 0.5);
  }

  .definition-list dd,
  .metric-list strong,
  .diagnostic-grid strong {
    margin: 8px 0 0;
    display: block;
    font-size: 1.1rem;
  }

  .answer-block,
  .sentence-list,
  .citation-list,
  .stream-list {
    display: grid;
    gap: 12px;
  }

  .answer-lead {
    margin: 0;
    padding: 16px 18px;
    border-radius: 18px;
    background: rgba(17, 27, 38, 0.06);
    line-height: 1.65;
    white-space: pre-wrap;
  }

  .sentence-card,
  .citation-card,
  .stream-event {
    padding: 16px 18px;
    border-radius: 18px;
    background: rgba(255, 255, 255, 0.72);
    border: 1px solid rgba(23, 36, 48, 0.08);
  }

  .citation-card.selected {
    border-color: rgba(29, 92, 149, 0.42);
    box-shadow: 0 0 0 4px rgba(29, 92, 149, 0.12);
  }

  .citation-pills {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    justify-content: flex-end;
  }

  .citation-pill {
    border: 1px solid rgba(17, 27, 38, 0.12);
    background: rgba(17, 27, 38, 0.04);
    color: #10202f;
    padding: 6px 10px;
    min-height: 0;
    font-size: 0.78rem;
  }

  .citation-pill:hover:enabled {
    box-shadow: none;
    transform: translateY(-1px);
  }

  .stream-event pre {
    margin: 10px 0 0;
    white-space: pre-wrap;
    word-break: break-word;
    font-family: 'SFMono-Regular', Consolas, monospace;
    font-size: 0.82rem;
  }

  .result-card-grid {
    display: grid;
    gap: 14px;
  }

  .result-card {
    padding: 16px 18px;
    border-radius: 18px;
    background: rgba(255, 255, 255, 0.72);
    border: 1px solid rgba(23, 36, 48, 0.08);
  }

  .status-badge {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    padding: 6px 10px;
    border-radius: 999px;
    font-size: 0.74rem;
    letter-spacing: 0.08em;
    text-transform: uppercase;
  }

  .status-changed {
    background: rgba(70, 123, 255, 0.14);
    color: #1c4fb6;
  }

  .status-ingested {
    background: rgba(24, 122, 77, 0.14);
    color: #157046;
  }

  .status-skipped {
    background: rgba(130, 98, 28, 0.14);
    color: #8a6114;
  }

  .status-failed {
    background: rgba(180, 42, 42, 0.14);
    color: #9e2020;
  }

  .filter-row button {
    cursor: pointer;
    user-select: none;
  }

  .filter-row button {
    border: 0;
    appearance: none;
  }

  .filter-row button.active {
    background: rgba(17, 27, 38, 0.92);
    color: #f8f1e7;
  }

  .result-card p {
    margin: 10px 0 0;
    line-height: 1.55;
    white-space: pre-wrap;
  }

  .result-card .muted {
    color: #5f6e79;
    font-size: 0.84rem;
    word-break: break-word;
  }

  .preview-grid {
    display: grid;
    gap: 12px;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    margin-top: 12px;
  }

  .preview-grid article {
    padding: 12px 14px;
    border-radius: 16px;
    background: rgba(17, 27, 38, 0.05);
  }

  .preview-grid article span {
    display: block;
    margin-bottom: 8px;
    color: #51606d;
    font-size: 0.74rem;
    letter-spacing: 0.08em;
    text-transform: uppercase;
  }

  .preview-grid pre {
    margin: 0;
    white-space: pre-wrap;
    word-break: break-word;
    font-family: 'SFMono-Regular', Consolas, monospace;
    font-size: 0.8rem;
    line-height: 1.5;
  }

  .hit-list {
    display: grid;
    gap: 14px;
  }

  .hit-card {
    padding: 16px;
    border-radius: 18px;
    background: linear-gradient(180deg, rgba(255, 255, 255, 0.72), rgba(245, 236, 224, 0.88));
    border: 1px solid rgba(17, 27, 38, 0.08);
  }

  .hit-card p {
    margin: 10px 0 0;
    line-height: 1.55;
    white-space: pre-wrap;
  }

  .tag-row,
  .chip-field {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 12px;
  }

  .chip-field.compact {
    margin-top: 12px;
  }

  .tag-row span,
  .chip-field span {
    padding: 7px 10px;
    border-radius: 999px;
    background: rgba(248, 241, 231, 0.16);
    color: #ffffff;
    font-size: 0.84rem;
    border: 1px solid rgba(248, 241, 231, 0.1);
  }

  @keyframes rise {
    from {
      opacity: 0;
      transform: translateY(18px);
    }

    to {
      opacity: 1;
      transform: translateY(0);
    }
  }

  @media (max-width: 1100px) {
    .hero,
    .tab-layout {
      grid-template-columns: 1fr;
    }
  }

  @media (max-width: 720px) {
    .shell {
      padding: 20px 14px 32px;
    }

    .hero-stats,
    .grid.two,
    .sample-banner,
    .preview-grid {
      grid-template-columns: 1fr;
    }

    .panel-header,
    .button-row,
    .hit-topline {
      flex-direction: column;
      align-items: stretch;
    }
  }
</style>
