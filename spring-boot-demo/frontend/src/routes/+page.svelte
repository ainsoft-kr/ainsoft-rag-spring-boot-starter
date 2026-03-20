<svelte:head>
  <title>Ainsoft RAG Demo</title>
  <meta
    name="description"
    content="Spring Boot static hosting demo for Ainsoft RAG with a SvelteKit frontend."
  />
</svelte:head>

<script>
  import { onMount } from 'svelte';
  import { apiGet, apiPost, apiUpload } from '$lib/api';

  const sampleDefaults = {
    tenantId: 'tenant-web-demo',
    principals: 'group:demo',
    query: 'hybrid retrieval',
    docIds: ['product-overview', 'ops-runbook', 'retrieval-notes']
  };

  const defaults = {
    tenantId: sampleDefaults.tenantId,
    docId: 'notes-001',
    acl: sampleDefaults.principals,
    principals: sampleDefaults.principals,
    query: sampleDefaults.query,
    metadata: 'category=notes\nsurface=ui',
    text: `Ainsoft RAG demo note\n\nThis document is indexed through the Spring Boot demo API.\nUse the search form to verify retrieval, ACL filtering, and provider telemetry.`,
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
  let loading = {
    sample: false,
    ingest: false,
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
    docIds: sampleDefaults.docIds
  };
  let ingestResponse = null;
  let searchResponse = null;
  let answerResponse = null;
  let answerStreamEvents = [];
  let selectedCitationIndex = null;
  let diagnoseResponse = null;
  let statsResponse = null;
  let healthResponse = null;

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

  function pushNotice(kind, message) {
    notifications = [{ id: crypto.randomUUID(), kind, message }, ...notifications].slice(0, 4);
  }

  function setLoading(key, value) {
    loading = { ...loading, [key]: value };
  }

  function buildSearchPayload() {
    return {
      tenantId: searchForm.tenantId,
      principals: toList(searchForm.principals),
      query: searchForm.query,
      topK: Number(searchForm.topK),
      filter: toMap(searchForm.filter),
      providerHealthDetail: true,
      recentProviderWindowMillis: Number(searchForm.recentProviderWindowMillis)
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
        pushNotice('success', successMessage);
      }
      return result;
    } catch (error) {
      pushNotice('error', error instanceof Error ? error.message : 'Unexpected error');
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

  async function loadSampleData() {
    const response = await withStatus('sample', '샘플 데이터를 준비했습니다.', () =>
      apiPost('/api/rag/demo/load-sample', {})
    );
    applySamplePreset(response);
    await Promise.all([refreshStats(), refreshHealth()]);
    await submitSearch();
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
    statsTenantId = ingestForm.tenantId;
    searchForm = { ...searchForm, tenantId: ingestForm.tenantId };
    await refreshStats();
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

    ingestResponse = await withStatus('upload', '파일이 업로드되어 색인되었습니다.', () =>
      apiUpload('/api/rag/ingest-file', formData)
    );
    statsTenantId = uploadForm.tenantId;
    searchForm = { ...searchForm, tenantId: uploadForm.tenantId };
    await refreshStats();
  }

  async function submitSearch() {
    searchResponse = await withStatus('search', '검색 결과를 갱신했습니다.', () =>
      apiPost('/api/rag/search', {
        ...buildSearchPayload(),
        openSource: searchForm.openSource
      })
    );
  }

  async function submitAnswer() {
    answerResponse = await withStatus('answer', '답변을 생성했습니다.', () =>
      apiPost('/api/rag/answer', buildSearchPayload())
    );
    selectedCitationIndex = null;
  }

  async function submitAnswerStream() {
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
    diagnoseResponse = await withStatus('diagnose', '검색 진단 결과를 갱신했습니다.', () =>
      apiPost('/api/rag/diagnose-search', {
        tenantId: searchForm.tenantId,
        principals: toList(searchForm.principals),
        query: searchForm.query,
        topK: Number(searchForm.topK),
        filter: toMap(searchForm.filter),
        providerHealthDetail: true,
        recentProviderWindowMillis: Number(searchForm.recentProviderWindowMillis)
      })
    );
  }

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
    await Promise.allSettled([submitSearch()]);
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
      <h1>정적 서빙되는 SvelteKit UI로 색인, 검색, 진단을 한 화면에서 확인합니다.</h1>
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
        <button
          data-testid="load-sample-button"
          disabled={loading.sample}
          on:click={loadSampleData}
        >
          {loading.sample ? '로딩 중...' : 'Load Sample Data'}
        </button>
      </div>

      <div class="chip-field compact">
        {#each sampleResponse.docIds as docId}
          <span>{docId}</span>
        {/each}
      </div>
    </div>

    <div class="hero-stats">
      {#each statCards as card}
        <article class="stat-card" data-testid={`stat-${card.label.toLowerCase().replace(/\s+/g, '-')}`}>
          <span>{card.label}</span>
          <strong>{card.value()}</strong>
          <small>{card.helper}</small>
        </article>
      {/each}
    </div>
  </section>

  {#if notifications.length}
    <section class="notices" data-testid="notifications">
      {#each notifications as notice (notice.id)}
        <div class:success={notice.kind === 'success'} class:error={notice.kind === 'error'} class="notice">
          <span>{notice.kind === 'success' ? 'OK' : 'ERR'}</span>
          <p>{notice.message}</p>
        </div>
      {/each}
    </section>
  {/if}

  <section class="workspace">
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
          <textarea bind:value={ingestForm.text} data-testid="ingest-text" rows="8"></textarea>
        </label>
      </article>

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

      <article class="panel">
        <div class="panel-header">
          <h2>검색과 진단</h2>
          <div class="button-row">
            <button data-testid="search-submit" disabled={loading.search} on:click={submitSearch}>
              {loading.search ? '검색 중...' : 'Search'}
            </button>
            <button data-testid="answer-submit" disabled={loading.answer} on:click={submitAnswer}>
              {loading.answer ? '생성 중...' : 'Answer'}
            </button>
            <button
              class="ghost"
              data-testid="answer-stream-submit"
              disabled={loading.answerStream}
              on:click={submitAnswerStream}
            >
              {loading.answerStream ? '스트리밍 중...' : 'Stream'}
            </button>
            <button
              class="ghost"
              data-testid="diagnose-submit"
              disabled={loading.diagnose}
              on:click={runDiagnostics}
            >
              {loading.diagnose ? '진단 중...' : 'Diagnose'}
            </button>
          </div>
        </div>

        <div class="grid two">
          <label>
            <span>Tenant</span>
            <input bind:value={searchForm.tenantId} data-testid="search-tenant" />
          </label>
          <label>
            <span>Top K</span>
            <input bind:value={searchForm.topK} min="1" max="20" type="number" />
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
            <input bind:value={searchForm.recentProviderWindowMillis} type="number" min="0" />
          </label>

          <label class="checkbox">
            <span>Open Source Snippet</span>
            <input bind:checked={searchForm.openSource} type="checkbox" />
          </label>
        </div>
      </article>

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

      <article class="panel result-panel">
        <div class="panel-header">
          <h2>Provider Health</h2>
          <span class="pill">{healthResponse?.providerTelemetry?.requestCount ?? 0} req</span>
        </div>

        {#if healthResponse}
          <div class="metric-list">
            <div>
              <span>Global Count</span>
              <strong>{healthResponse.providerTelemetry.requestCount}</strong>
            </div>
            <div>
              <span>Recent Count</span>
              <strong>{healthResponse.recentProviderTelemetry?.requestCount ?? 0}</strong>
            </div>
            <div>
              <span>Avg Latency</span>
              <strong>{healthResponse.providerTelemetry.avgLatencyMillis ?? 0} ms</strong>
            </div>
            <div>
              <span>Success Rate</span>
              <strong>{healthResponse.providerTelemetry.successRatePct ?? 0}%</strong>
            </div>
          </div>

          {#if healthResponse.providerTelemetry.commandScopes?.length}
            <div class="chip-field">
              {#each healthResponse.providerTelemetry.commandScopes as scope}
                <span>{scope.scope}: {scope.requestCount}</span>
              {/each}
            </div>
          {/if}
        {:else}
          <p class="empty">Provider 상태를 불러오면 호출량과 성공률이 표시됩니다.</p>
        {/if}
      </article>
    </div>
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
  .notices {
    position: relative;
    z-index: 1;
    max-width: 1440px;
    margin: 0 auto;
  }

  .hero {
    display: grid;
    grid-template-columns: 1.2fr 0.8fr;
    gap: 24px;
    margin-bottom: 24px;
    animation: rise 500ms ease-out both;
  }

  .hero-copy,
  .stat-card,
  .panel,
  .notice {
    border: 1px solid rgba(255, 255, 255, 0.12);
    box-shadow: 0 24px 80px rgba(5, 16, 25, 0.12);
    backdrop-filter: blur(14px);
  }

  .hero-copy {
    padding: 28px;
    border-radius: 28px;
    background: rgba(7, 18, 27, 0.76);
    color: #f8f1e7;
  }

  .eyebrow {
    margin: 0 0 14px;
    color: #f9c76d;
    font-size: 0.78rem;
    letter-spacing: 0.22em;
  }

  h1,
  h2,
  strong {
    font-family: Georgia, 'Times New Roman', serif;
  }

  h1 {
    margin: 0;
    font-size: clamp(2rem, 4vw, 3.4rem);
    line-height: 1.02;
  }

  .lede {
    max-width: 62ch;
    margin: 18px 0 0;
    color: rgba(248, 241, 231, 0.82);
    line-height: 1.6;
  }

  .sample-banner {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 12px;
    align-items: end;
    margin-top: 20px;
    padding: 16px;
    border-radius: 18px;
    background: rgba(248, 241, 231, 0.12);
  }

  .sample-label {
    display: block;
    color: rgba(248, 241, 231, 0.68);
    font-size: 0.72rem;
    letter-spacing: 0.08em;
    text-transform: uppercase;
  }

  .sample-banner strong {
    display: block;
    margin-top: 6px;
    font-size: 1.05rem;
  }

  .hero-stats {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 16px;
    align-content: stretch;
  }

  .stat-card {
    padding: 22px;
    border-radius: 24px;
    background: rgba(248, 241, 231, 0.84);
    animation: rise 650ms ease-out both;
  }

  .stat-card span,
  .panel label span,
  .metric-list span,
  .definition-list dt {
    display: block;
    color: #51606d;
    font-size: 0.82rem;
    letter-spacing: 0.06em;
    text-transform: uppercase;
  }

  .stat-card strong {
    display: block;
    margin: 14px 0 6px;
    font-size: 2rem;
    color: #0f1720;
  }

  .notices {
    display: grid;
    gap: 10px;
    margin-bottom: 20px;
  }

  .notice {
    display: grid;
    grid-template-columns: 48px 1fr;
    align-items: center;
    gap: 12px;
    padding: 14px 16px;
    border-radius: 18px;
    background: rgba(248, 241, 231, 0.84);
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
    grid-template-columns: 0.95fr 1.05fr;
    gap: 22px;
  }

  .controls,
  .results {
    display: grid;
    gap: 18px;
    align-content: start;
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
    margin-top: 16px;
  }

  .tag-row span,
  .chip-field span {
    padding: 7px 10px;
    border-radius: 999px;
    background: rgba(17, 27, 38, 0.08);
    color: #1b3244;
    font-size: 0.84rem;
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
    .workspace {
      grid-template-columns: 1fr;
    }
  }

  @media (max-width: 720px) {
    .shell {
      padding: 20px 14px 32px;
    }

    .hero-stats,
    .grid.two,
    .sample-banner {
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
