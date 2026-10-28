import { normalizePath, parseDiff } from './lib/diff.js';
import {
  buildModelUpdate,
  classifyModelError,
  describeModelSaved,
  errorText
} from './lib/model-settings.js';
import {
  expandQuickCommand,
  filterPaletteActions,
  filterQuickCommands
} from './lib/commands.js';
import { rankFileMatches } from './lib/mention.js';
import { mergeRunReports, sortChangeFiles, statusKind } from './lib/changes.js';
import { applyRule, classifyRunFailure, planProgressLabel } from './lib/events.js';
import { groupSessionsByProject, resolveFolders, samePath } from './lib/projects.js';
import { commandStatusLabel, isCommandActive, mergeCommandLog } from './lib/command-tasks.js';

/** 原生 fetch 别名：apiFetch 内部用它，避免自己调用自己。 */
const NATIVE_FETCH = window.fetch.bind(window);
/** 普通接口的默认超时。SSE 走 EventSource，不经过这里。 */
const REQUEST_TIMEOUT_MS = 20000;
const COMMAND_LOG_CHUNK_LIMIT = 65536;

/**
 * 统一请求入口：带超时与取消。
 *
 * <p>后端卡住或网络断开时，裸 fetch 会一直挂着，界面停在「加载中」且没法重试；
 * 这里用 AbortController 兜底，并把超时报成可读的提示。
 */
async function apiFetch(input, init = {}, timeoutMs = REQUEST_TIMEOUT_MS) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await NATIVE_FETCH(input, { ...init, signal: controller.signal });
  } catch (error) {
    if (error && error.name === 'AbortError') {
      throw new Error(`请求超时（${Math.round(timeoutMs / 1000)} 秒）`);
    }
    throw error;
  } finally {
    clearTimeout(timer);
  }
}

const EVENT_TYPES = [
  'run.started',
  'assistant.delta',
  'assistant.preview',
  'context.compacted',
  'plan.updated',
  'run.steered',
  'run.continued',
  'run.retrying',
  'subagent.started',
  'subagent.finished',
  'task.updated',
  'sandbox.diff',
  'arbitration.required',
  'arbitration.resolved',
  'tool.started',
  'approval.required',
  'approval.resolved',
  'tool.completed',
  'run.failed',
  'run.cancelled',
  'run.completed'
];

const el = {
  app: document.querySelector('#app'),
  sidebarToggle: document.querySelector('#sidebar-toggle'),
  scrim: document.querySelector('#scrim'),
  permChip: document.querySelector('#perm-chip'),
  permMenu: document.querySelector('#perm-menu'),
  modelChip: document.querySelector('#model-chip'),
  modelMenu: document.querySelector('#model-menu'),
  modelOptions: document.querySelector('#model-options'),
  modelName: document.querySelector('#model-name'),
  modelEndpoint: document.querySelector('#model-endpoint'),
  modelKey: document.querySelector('#model-key'),
 modelClearKey: document.querySelector('#model-clear-key'),
  modelHint: document.querySelector('#model-hint'),
  modelSave: document.querySelector('#model-save'),
  attachments: document.querySelector('#attachments'),
  workspaceChip: document.querySelector('#workspace-chip'),
  workspaceName: document.querySelector('#workspace-name'),
  sessionChip: document.querySelector('#session-chip'),
  connection: document.querySelector('#connection'),
  connectionText: document.querySelector('#connection-text'),
  newSession: document.querySelector('#new-session'),
  sessionList: document.querySelector('#session-list'),
  sessionEmpty: document.querySelector('#session-empty'),
  crumbTitle: document.querySelector('#crumb-title'),
  notice: document.querySelector('#notice'),
  noticeText: document.querySelector('#notice-text'),
  noticeClose: document.querySelector('#notice-close'),
  runState: document.querySelector('#run-state'),
  themeToggle: document.querySelector('#theme-toggle'),
  messages: document.querySelector('#messages'),
  emptyState: document.querySelector('#empty-state'),
  prompts: document.querySelector('#prompts'),
  approvalDock: document.querySelector('#approval-dock'),
  messageQueue: document.querySelector('#message-queue'),
  taskContract: document.querySelector('#task-contract'),
  taskSummary: document.querySelector('#task-contract-summary'),
  taskGoal: document.querySelector('#task-original-goal'),
  taskConstraints: document.querySelector('#task-constraints'),
  taskAcceptance: document.querySelector('#task-acceptance'),
  taskAddCriterion: document.querySelector('#task-add-criterion'),
  taskCorrections: document.querySelector('#task-corrections'),
  taskHint: document.querySelector('#task-contract-hint'),
  taskSave: document.querySelector('#task-contract-save'),
  taskDraft: document.querySelector('#task-draft'),
  taskDraftConstraints: document.querySelector('#task-draft-constraints'),
  taskDraftAcceptance: document.querySelector('#task-draft-acceptance'),
  taskLiveControls: document.querySelector('#task-live-controls'),
  taskLiveHint: document.querySelector('#task-live-hint'),
  taskSteer: document.querySelector('#task-steer'),
  taskPause: document.querySelector('#task-pause'),
  taskCancel: document.querySelector('#task-cancel'),
  taskResumeControls: document.querySelector('#task-resume-controls'),
  taskResume: document.querySelector('#task-resume'),
  sendModeLabel: document.querySelector('#send-mode-label'),
  toggleActivity: document.querySelector('#toggle-activity'),
  activity: document.querySelector('#activity'),
  railToggle: document.querySelector('#rail-toggle'),
  sessionCount: document.querySelector('#session-count'),
  tree: document.querySelector('#file-tree'),
  treeEmpty: document.querySelector('#tree-empty'),
  treeRefresh: document.querySelector('#tree-refresh'),
  panelPreview: document.querySelector('#panel-preview'),
  previewPath: document.querySelector('#preview-path'),
  previewBody: document.querySelector('#preview-body'),
  previewRefresh: document.querySelector('#preview-refresh'),
  refMenu: document.querySelector('#ref-menu'),
  gutterLeft: document.querySelector('#gutter-left'),
  gutterRight: document.querySelector('#gutter-right'),
  filters: document.querySelector('#filters'),
  panelTabs: document.querySelector('#panel-tabs'),
  panelActivity: document.querySelector('#panel-activity'),
  panelChanges: document.querySelector('#panel-changes'),
  panelCommands: document.querySelector('#panel-commands'),
  commandsCount: document.querySelector('#commands-count'),
  commandsState: document.querySelector('#commands-state'),
  commandsRefresh: document.querySelector('#commands-refresh'),
  commandsEmpty: document.querySelector('#commands-empty'),
  commandsList: document.querySelector('#commands-list'),
  commandDetail: document.querySelector('#command-detail'),
  commandTitle: document.querySelector('#command-title'),
  commandMeta: document.querySelector('#command-meta'),
  commandReason: document.querySelector('#command-reason'),
  commandStop: document.querySelector('#command-stop'),
  commandLog: document.querySelector('#command-log'),
  commandLogNote: document.querySelector('#command-log-note'),
  commandLogBottom: document.querySelector('#command-log-bottom'),
  changeCount: document.querySelector('#change-count'),
  changesState: document.querySelector('#changes-state'),
  changesRefresh: document.querySelector('#changes-refresh'),
  changesRollback: document.querySelector('#changes-rollback'),
  branchCreate: document.querySelector('#branch-create'),
  runChanges: document.querySelector('#run-changes'),
  changeFiles: document.querySelector('#change-files'),
  changeDiff: document.querySelector('#change-diff'),
  changesTotals: document.querySelector('#changes-totals'),
  changeViewBar: document.querySelector('#change-view-bar'),
  changeViewPath: document.querySelector('#change-view-path'),
  changeViewStat: document.querySelector('#change-view-stat'),
  changeViewPreview: document.querySelector('#change-view-preview'),
  settings: document.querySelector('#settings'),
  settingsOpen: document.querySelector('#settings-open'),
  settingsClose: document.querySelector('#settings-close'),
  settingsNav: document.querySelector('#settings-nav'),
  themeOptions: document.querySelector('#theme-options'),
  accentOptions: document.querySelector('#accent-options'),
  settingsModelOptions: document.querySelector('#settings-model-options'),
  settingsModelName: document.querySelector('#settings-model-name'),
  settingsModelEndpoint: document.querySelector('#settings-model-endpoint'),
  settingsModelKey: document.querySelector('#settings-model-key'),
  settingsModelClearKey: document.querySelector('#settings-model-clear-key'),
  settingsModelSave: document.querySelector('#settings-model-save'),
  settingsModelHint: document.querySelector('#settings-model-hint'),
  permissionOptions: document.querySelector('#permission-options'),
  settingsPermissionHint: document.querySelector('#settings-permission-hint'),
  aboutWorkspace: document.querySelector('#settings-about-workspace'),
  aboutConnection: document.querySelector('#settings-about-connection'),
  aboutModel: document.querySelector('#settings-about-model'),
  aboutPermission: document.querySelector('#settings-about-permission'),
  timeline: document.querySelector('#timeline'),
  timelineEmpty: document.querySelector('#timeline-empty'),
  composer: document.querySelector('#composer'),
  dropVeil: document.querySelector('#drop-veil'),
  input: document.querySelector('#input'),
  send: document.querySelector('#send'),
  palette: document.querySelector('#palette'),
  paletteInput: document.querySelector('#palette-input'),
  paletteList: document.querySelector('#palette-list'),
  picker: document.querySelector('#picker'),
  pickerClose: document.querySelector('#picker-close'),
  pickerRoots: document.querySelector('#picker-roots'),
  pickerUp: document.querySelector('#picker-up'),
  pickerPath: document.querySelector('#picker-path'),
  pickerGo: document.querySelector('#picker-go'),
  pickerList: document.querySelector('#picker-list'),
  pickerError: document.querySelector('#picker-error'),
  pickerSelected: document.querySelector('#picker-selected'),
  pickerConfirm: document.querySelector('#picker-confirm'),
  newProject: document.querySelector('#new-project'),
  projectModal: document.querySelector('#project-modal'),
  projectModalTitle: document.querySelector('#project-modal-title'),
  projectClose: document.querySelector('#project-close'),
  projectName: document.querySelector('#project-name'),
  projectAddFolder: document.querySelector('#project-add-folder'),
  projectFolders: document.querySelector('#project-folders'),
  projectHint: document.querySelector('#project-hint'),
  projectSave: document.querySelector('#project-save'),
  projectDelete: document.querySelector('#project-delete'),
  folderMenu: document.querySelector('#folder-menu')
};

const LAST_WORKSPACE_KEY = 'pilot.workspace';
const LAST_SESSION_KEY = 'pilot.session';
/** 超过这个秒数没有新事件，就提示「可能卡住」，让用户知道还能停止。 */
const STALL_SECONDS = 75;

const state = {
  sessionId: null,
  sessionTitle: '',
  /** 项目列表：{ id, name, folders, sessionCount } */
  projects: [],
  /** 当前项目；新建对话挂在它下面 */
  currentProjectId: null,
  /** 项目编辑弹窗里的临时状态：{ id, name, folders }，id 为空表示新建 */
  projectEditor: null,
  /** picker 被项目弹窗借用时的回调；为空表示「选完就开新对话」 */
  pickerOnPick: null,
  workspace: null,
  runId: null,
  lastRunId: null,
  runStartedAt: null,
  lastEventAt: null,
  /** 模型请求失败后的等待：{ until }，计时器在这段时间里显示倒计时 */
  modelRetryUntil: 0,
  clock: null,
  cursor: 0,
  /** SSE 重连次数，用于指数退避与「第 N 次重连」提示 */
  reconnectAttempt: 0,
  reconnectTimer: null,
  permissionMode: null,
  /** 每次向前翻页取多少条历史；翻页用 before 游标，不再从全量里切片 */
  historyPageSize: 80,
  historyHasMore: false,
  /** 当前已渲染的事件窗口（升序）。翻页时只把新一页并进来，避免重复拉取整段历史 */
  windowEvents: [],
  replaying: false,
  changedFiles: [],
  /** 当前这一轮里出现过的 runId：自动继续会换 run，改动要合并展示 */
  turnRuns: [],
  composerEnabled: false,
  streamCard: null,
  streamText: '',
  renderQueued: false,
  sessions: [],
  source: null,
  running: false,
  thinking: null,
  reply: null,
  replyText: '',
  replyLive: false,
  replyCommitted: '',
  /** 当前这一轮的外壳：操作组在前，结论文本在后 */
  turnShell: null,
  toolRows: new Map(),
  pendingInputs: [],
  inputHistory: [],
  inputHistoryIndex: -1,
  inputHistoryDraft: '',
  queue: [],
  queueCollapsed: false,
  task: null,
  taskDirty: false,
  taskBusy: false,
  taskLoadVersion: 0,
  taskRefreshTimer: null,
  steering: false,
  pausing: false,
  /** 正在改队列里的某一条：{ index, previous }，previous 是进编辑前输入框里的草稿 */
  queueEdit: null,
  attachments: [],
  modelOptions: [],
  modelApiKeySet: false,
  modelApiKeyMasked: '',
  /** 外观偏好：light / dark / system，真正生效的是解析后的 light/dark */
  themeMode: 'light',
  accent: 'blue',
  /** /api/settings 的最近一次响应，设置面板和关于页共用 */
  settingsData: null,
  settingsSection: 'appearance',
  changeFiles: [],
  changeDiffByPath: new Map(),
  changeSelected: null,
  changeReport: null,
  previewPath: null,
  refKind: null,
  refItems: [],
  refIndex: 0,
  pendingApprovals: new Map(),
  activityCount: 0,
  filter: 'all',
  tab: 'activity',
  pickerPath: '',
  pickerParent: null,
  pickerSelection: '',
  paletteIndex: 0,
  paletteItems: []
};

const COMMAND_ACTIONS = {
  'new-session': () => openPicker(),
  'toggle-activity': () => toggleActivity(),
  'open-settings': () => openSettings(),
  'refresh-changes': () => selectTab('changes'),
  'copy-session-id': copySessionId,
  'reset-view': () => resetView(),
  reconnect: () => reconnect()
};

const commandView = {
  tasks: [], selectedId: null, detail: null, log: emptyCommandLog(),
  version: 0, timer: null, controller: null, busy: false,
  stopController: null, stoppingId: null, loadingLog: false, loaded: false
};

const THEME_KEY = 'pilot.theme';
const ACCENT_KEY = 'pilot.accent';

const THEME_MODES = [
  { mode: 'light', label: '浅色', hint: '始终亮色' },
  { mode: 'dark', label: '深色', hint: '始终暗色' },
  { mode: 'system', label: '跟随系统', hint: '随系统切换' }
];

const ACCENTS = [
  { name: 'blue', label: '靛蓝' },
  { name: 'violet', label: '紫罗兰' },
  { name: 'green', label: '松绿' },
  { name: 'amber', label: '琥珀' },
  { name: 'rose', label: '玫红' }
];

const PERMISSION_TITLES = {
  READ_ONLY: '只读',
  ASK: '逐次批准',
  AUTO_EDIT: '自动改文件',
  AUTO: '完全放行'
};

init();

/** 隐私模式下读不了 localStorage，退回默认值。 */
function readStored(key, fallback) {
  try {
    return localStorage.getItem(key) || fallback;
  } catch (error) {
    return fallback;
  }
}

function writeStored(key, value) {
  try {
    localStorage.setItem(key, value);
  } catch (error) {
    // 隐私模式下写不了 localStorage，不影响本次切换
  }
}

function prefersDarkTheme() {
  return Boolean(window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches);
}

/** 偏好可以是 light / dark / system，落到 DOM 上的只有 light / dark。 */
function resolveTheme(mode) {
  if (mode === 'system') {
    return prefersDarkTheme() ? 'dark' : 'light';
  }
  return mode === 'dark' ? 'dark' : 'light';
}

/** 主题只切换 CSS 变量，不需要重绘任何组件；顺便记住上次选择。 */
function applyTheme(mode, options = {}) {
  const next = THEME_MODES.some((item) => item.mode === mode) ? mode : 'light';
  const resolved = resolveTheme(next);
  state.themeMode = next;
  document.documentElement.dataset.theme = resolved;
  document.documentElement.dataset.themeMode = next;
  if (options.persist !== false) {
    writeStored(THEME_KEY, next);
  }
  el.themeToggle.setAttribute('aria-pressed', String(resolved === 'dark'));
  el.themeToggle.title = resolved === 'dark'
    ? '切换到亮色（设置里可以跟随系统）'
    : '切换到暗色（设置里可以跟随系统）';
  renderThemeOptions();
}

function applyAccent(name, options = {}) {
  const next = ACCENTS.some((item) => item.name === name) ? name : 'blue';
  state.accent = next;
  document.documentElement.dataset.accent = next;
  if (options.persist !== false) {
    writeStored(ACCENT_KEY, next);
  }
  renderAccentOptions();
}

/** 顶栏按钮只负责明暗切换；要「跟随系统」去设置面板。 */
function toggleTheme() {
  applyTheme(resolveTheme(state.themeMode) === 'dark' ? 'light' : 'dark');
}

/** 选了「跟随系统」时，系统主题变化要实时跟着换。 */
function bindThemeMedia() {
  if (!window.matchMedia) {
    return;
  }
  const query = window.matchMedia('(prefers-color-scheme: dark)');
  const onChange = () => {
    if (state.themeMode === 'system') {
      applyTheme('system', { persist: false });
    }
  };
  if (query.addEventListener) {
    query.addEventListener('change', onChange);
  } else if (query.addListener) {
    query.addListener(onChange);
  }
}

function init() {
  applyTheme(readStored(THEME_KEY, 'light'), { persist: false });
  applyAccent(readStored(ACCENT_KEY, 'blue'), { persist: false });
  bindThemeMedia();
  el.themeToggle.addEventListener('click', toggleTheme);
  el.settingsOpen.addEventListener('click', () => openSettings());
  el.settingsClose.addEventListener('click', closeSettings);
  el.settings.addEventListener('click', (event) => {
    if (event.target === el.settings) {
      closeSettings();
    }
  });
  el.settingsNav.addEventListener('click', (event) => {
    const tab = event.target.closest('.settings-tab');
    if (tab) {
      showSettingsSection(tab.dataset.section);
    }
  });
  el.settingsModelSave.addEventListener('click', saveModelFromSettings);
  el.settingsModelClearKey.addEventListener('change', onSettingsClearKeyToggle);
  el.changeFiles.addEventListener('keydown', onChangeListKeydown);
  el.changeViewPreview.addEventListener('click', () => {
    if (state.changeSelected) {
      openPreview(state.changeSelected);
    }
  });
  el.newSession.addEventListener('click', beginNewConversation);
  el.newProject.addEventListener('click', () => openProjectModal());
  el.projectClose.addEventListener('click', closeProjectModal);
  el.projectAddFolder.addEventListener('click', addProjectFolder);
  el.projectSave.addEventListener('click', saveProject);
  el.projectDelete.addEventListener('click', deleteProject);
  el.projectModal.addEventListener('click', (event) => {
    if (event.target === el.projectModal) {
      closeProjectModal();
    }
  });
  el.projectName.addEventListener('keydown', (event) => {
    if (event.key === 'Enter') {
      event.preventDefault();
      saveProject();
    }
  });
  el.workspaceName.addEventListener('click', toggleFolderMenu);
  el.permChip.addEventListener('click', () => togglePermissionMenu());
  el.modelChip.addEventListener('click', () => toggleModelMenu());
  el.modelSave.addEventListener('click', saveModel);
  el.modelClearKey.addEventListener('change', onModelClearKeyToggle);
  el.input.addEventListener('paste', onPasteAttachments);
  bindFileDrop();
  document.addEventListener('click', (event) => {
    if (!el.permMenu.hidden && !event.target.closest('.perm-picker')) {
      togglePermissionMenu(false);
    }
    if (!el.modelMenu.hidden && !event.target.closest('.perm-picker')) {
      toggleModelMenu(false);
    }
    if (!el.folderMenu.hidden && !event.target.closest('#folder-menu')
        && !event.target.closest('#workspace-name')) {
      el.folderMenu.hidden = true;
    }
  });
  el.workspaceChip.addEventListener('click', () => openPicker());
  el.sessionChip.addEventListener('click', copySessionId);
  el.sidebarToggle.addEventListener('click', toggleSidebar);
  el.railToggle.addEventListener('click', toggleSidebar);
  el.treeRefresh.addEventListener('click', () => loadTree(''));
  el.previewRefresh.addEventListener('click', () => {
    if (state.previewPath) {
      openPreview(state.previewPath);
    }
  });
  el.scrim.addEventListener('click', closeOverlays);
  el.toggleActivity.addEventListener('click', toggleActivity);
  el.filters.addEventListener('click', onFilterClick);
  el.prompts.addEventListener('click', onPromptClick);
  el.panelTabs.addEventListener('click', onTabClick);
  el.commandsRefresh.addEventListener('click', restartCommandPolling);
  el.commandStop.addEventListener('click', stopSelectedCommand);
  el.commandLogBottom.addEventListener('click', () => { el.commandLog.scrollTop = el.commandLog.scrollHeight; });
  document.addEventListener('visibilitychange', syncCommandPolling);
  window.addEventListener('pagehide', stopCommandRequests);
  window.addEventListener('beforeunload', stopCommandRequests);
  window.addEventListener('pageshow', syncCommandPolling);
  el.changesRefresh.addEventListener('click', () => loadChanges());
  el.changesRollback.addEventListener('click', rollbackRun);
  el.branchCreate.addEventListener('click', createWorkspaceBranch);
  el.send.addEventListener('click', onSendButtonClick);
  el.taskSteer.addEventListener('click', steerCurrentTask);
  el.taskPause.addEventListener('click', pauseCurrentTask);
  el.taskCancel.addEventListener('click', cancelRun);
  el.taskResume.addEventListener('click', () => continueRun(el.taskResumeControls));
  el.taskConstraints.addEventListener('input', markTaskDirty);
  el.taskSave.addEventListener('click', saveTaskContract);
  el.taskAddCriterion.addEventListener('click', () => {
    appendTaskCriterion({ text: '', checked: false, evidence: '' });
    markTaskDirty();
    el.taskAcceptance.lastElementChild.querySelector('.task-criterion-text').focus();
  });
  el.noticeClose.addEventListener('click', hideNotice);
  el.pickerClose.addEventListener('click', closePicker);
  el.pickerUp.addEventListener('click', pickerUp);
  el.pickerGo.addEventListener('click', () => browseTo(el.pickerPath.value.trim()));
  el.pickerPath.addEventListener('keydown', (event) => {
    if (event.key === 'Enter') {
      event.preventDefault();
      browseTo(el.pickerPath.value.trim());
    }
  });
  el.pickerConfirm.addEventListener('click', confirmPicker);
  el.picker.addEventListener('click', (event) => {
    if (event.target === el.picker) closePicker();
  });
  el.paletteInput.addEventListener('input', () => renderPalette(el.paletteInput.value));
  el.paletteInput.addEventListener('keydown', onPaletteKeydown);
  el.palette.addEventListener('click', (event) => {
    if (event.target === el.palette) closePalette();
  });
  document.addEventListener('keydown', onGlobalKeydown);
  window.addEventListener('resize', () => { syncScrim(); syncCommandPolling(); });
  bindComposer();
  initWorkbench();
  loadHealth();
  restoreLastSession();
}

/**
 * 刷新后优先恢复上次打开的会话。
 *
 * <p>会话和事件都在服务端，界面只需要重新拉一次列表并回放事件，不再每次刷新都新建会话。
 * 只有一条历史都没有时，才退回「按上次工作区新建」或首次选择目录。
 */
async function restoreLastSession() {
  setComposerEnabled(false);
  setRunState('idle', '加载历史');
  // 先取项目再取会话：侧栏要按项目分组，顺序反了会先闪一下「未分组」
  await loadProjects();
  const sessions = await loadSessions();
  const lastId = localStorage.getItem(LAST_SESSION_KEY);
  const lastProject = projectById(readStored(LAST_PROJECT_KEY, '')) || state.projects[0];

  // 上次打开的那条（未归档）优先；否则落到当前项目的空白对话——和 Codex 一样，
  // 打开项目就停在「可以立刻说话」的那条上，而不是翻出一堆历史。
  const remembered = sessions.find((item) => item.id === lastId && !item.archived);
  if (remembered) {
    activate(remembered.id, remembered.title, remembered.workspace);
    return;
  }
  const blank = lastProject
    ? sessions.find((item) => item.projectId === lastProject.id && item.isBlank && !item.archived)
    : null;
  if (blank) {
    activate(blank.id, blank.title, blank.workspace);
    return;
  }
  const first = sessions.find((item) => !item.archived);
  if (first) {
    activate(first.id, first.title, first.workspace);
    return;
  }
  if (lastProject) {
    newConversation(lastProject.id);
    return;
  }
  openPicker({ firstRun: true });
}

async function loadProjects() {
  try {
    const response = await apiFetch('/api/projects');
    if (!response.ok) {
      throw new Error('HTTP ' + response.status);
    }
    state.projects = await response.json();
    return state.projects;
  } catch (error) {
    console.warn('[projects]', error);
    showNotice('读取项目列表失败：' + error.message);
    return [];
  }
}

async function loadSessions() {
  try {
    const response = await apiFetch('/api/sessions');
    if (!response.ok) {
      throw new Error('HTTP ' + response.status);
    }
    const list = await response.json();
    state.sessions = list.map((item) => ({
      id: item.sessionId,
      title: item.title || '未命名会话',
      workspace: item.workspace,
      projectId: item.projectId || null,
      isBlank: item.isBlank === true,
      archived: item.archived === true,
      createdAt: item.createdAt,
      status: item.status
    }));
    renderSessions();
    return state.sessions;
  } catch (error) {
    console.warn('[sessions]', error);
    showNotice('读取会话列表失败：' + error.message);
    return [];
  }
}

/* ---------- 项目 ---------- */

const LAST_PROJECT_KEY = 'pilot.project';
/** 「未分组」不是真实项目，折叠状态用固定 key 存。 */
const UNGROUPED_KEY = '__ungrouped__';
/** 折叠的项目：纯界面状态，刷新后恢复成展开。 */
const collapsedProjects = new Set();

function currentProject() {
  return state.projects.find((project) => project.id === state.currentProjectId) || null;
}

/** 当前对话（或草稿）可用的文件夹列表；规则在 lib/projects.js 里单测。 */
function availableFolders() {
  return resolveFolders(currentProject(), state.workspace);
}

function projectById(id) {
  return state.projects.find((project) => project.id === id) || null;
}

/** 「新任务」入口：有项目就在当前项目下开一个空白对话，没有项目就先建项目。 */
function beginNewConversation() {
  const project = currentProject() || state.projects[0];
  if (!project) {
    openProjectModal();
    return;
  }
  newConversation(project.id);
}

/**
 * 在项目下开一个空白对话。
 *
 * <p>空白对话会真的落库（后端有 isBlank 标记），但同一项目只允许一条：
 * 已经有一条时后端返回 409，这里问用户「归档旧的再新建」还是「回去用旧的」。
 * 这样侧栏不会堆出一串没说过话的空壳。
 *
 * @param options.replaceBlank 用户已确认归档旧空白对话
 */
async function newConversation(projectId, options = {}) {
  const project = projectById(projectId);
  if (!project) {
    openProjectModal();
    return;
  }
  if (!project.folders.length) {
    showNotice('这个项目还没有绑定文件夹，先添加一个目录');
    openProjectModal(project.id);
    return;
  }
  try {
    const response = await apiFetch('/api/sessions', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        title: '新对话',
        projectId: project.id,
        workspace: options.workspace || project.folders[0],
        replaceBlank: options.replaceBlank === true
      })
    });
    if (response.status === 409) {
      resolveBlankConflict(project);
      return;
    }
    if (!response.ok) {
      throw new Error(await describeError(response));
    }
    const session = await response.json();
    state.currentProjectId = project.id;
    writeStored(LAST_PROJECT_KEY, project.id);
    await activate(session.sessionId, session.title, session.workspace || project.folders[0]);
    await Promise.all([loadSessions(), loadProjects()]);
    el.input.focus();
  } catch (error) {
    showNotice('创建对话失败：' + error.message);
  }
}

/** 已经有一条空白对话：让用户选「归档并新建」还是「去用旧的」，不静默处理。 */
function resolveBlankConflict(project) {
  const blank = state.sessions.find((session) => session.projectId === project.id
    && session.isBlank && !session.archived);
  if (!blank) {
    showNotice('该项目已有未使用的空白对话，请先刷新列表');
    loadSessions();
    return;
  }
  const archiveAndCreate = window.confirm(
    '「' + project.name + '」下已有一条还没发过消息的新对话。\n\n'
    + '确定：归档它并新建一条\n取消：回到那条已有的空白对话');
  if (archiveAndCreate) {
    newConversation(project.id, { replaceBlank: true });
    return;
  }
  activateSession(blank.id);
}

function toggleProject(key) {
  if (!key) {
    return;
  }
  if (collapsedProjects.has(key)) {
    collapsedProjects.delete(key);
  } else {
    collapsedProjects.add(key);
  }
  renderSessions();
}

function openProjectModal(projectId) {
  const project = projectId ? projectById(projectId) : null;
  state.projectEditor = project
    ? { id: project.id, name: project.name, folders: project.folders.slice() }
    : { id: null, name: '', folders: [] };
  el.projectModalTitle.textContent = project ? '项目设置' : '新建项目';
  el.projectName.value = state.projectEditor.name;
  el.projectDelete.hidden = !project;
  // 基线只展示不修改：改文件夹不会回头影响已经开着的对话
  el.projectHint.textContent = project && project.baseCommit
    ? `基线 ${String(project.baseCommit).slice(0, 7)} · 修改文件夹只影响之后新建的对话`
    : '';
  el.projectHint.className = 'settings-status';
  renderProjectFolders();
  el.projectModal.hidden = false;
  el.projectName.focus();
}

function closeProjectModal() {
  el.projectModal.hidden = true;
  state.projectEditor = null;
}

function renderProjectFolders() {
  const editor = state.projectEditor;
  el.projectFolders.replaceChildren();
  if (!editor || !editor.folders.length) {
    const empty = document.createElement('li');
    empty.className = 'project-folder-empty';
    empty.textContent = '还没有文件夹，点「添加文件夹」选一个目录。';
    el.projectFolders.append(empty);
    return;
  }
  for (const folder of editor.folders) {
    const item = document.createElement('li');
    item.className = 'project-folder';
    const path = document.createElement('span');
    path.className = 'project-folder-path mono';
    path.textContent = folder;
    path.title = folder;
    const remove = document.createElement('button');
    remove.type = 'button';
    remove.className = 'icon-btn icon-btn-sm';
    remove.title = '移除';
    remove.setAttribute('aria-label', '移除文件夹');
    remove.textContent = '✕';
    remove.addEventListener('click', () => {
      state.projectEditor.folders = state.projectEditor.folders.filter((item2) => item2 !== folder);
      renderProjectFolders();
    });
    item.append(path, remove);
    el.projectFolders.append(item);
  }
}

/** 借用目录选择器：选中的路径加进项目，不直接开对话。 */
function addProjectFolder() {
  if (!state.projectEditor) {
    return;
  }
  el.projectModal.hidden = true;
  openPicker({
    onPick: (path) => {
      if (!state.projectEditor) {
        return;
      }
      if (state.projectEditor.folders.includes(path)) {
        showNotice('这个文件夹已经在项目里了');
      } else {
        state.projectEditor.folders.push(path);
      }
      el.projectModal.hidden = false;
      renderProjectFolders();
    }
  });
}

async function saveProject() {
  const editor = state.projectEditor;
  if (!editor) {
    return;
  }
  const name = el.projectName.value.trim();
  if (!name) {
    setProjectHint('项目名称不能为空', true);
    return;
  }
  if (!editor.folders.length) {
    setProjectHint('至少添加一个文件夹', true);
    return;
  }
  el.projectSave.disabled = true;
  try {
    if (editor.id) {
      const renamed = await apiFetch(`/api/projects/${encodeURIComponent(editor.id)}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name })
      });
      if (!renamed.ok) {
        throw new Error(await describeError(renamed));
      }
      const updated = await apiFetch(`/api/projects/${encodeURIComponent(editor.id)}/folders`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ folders: editor.folders })
      });
      if (!updated.ok) {
        throw new Error(await describeError(updated));
      }
    } else {
      const created = await apiFetch('/api/projects', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name, folders: editor.folders })
      });
      if (!created.ok) {
        throw new Error(await describeError(created));
      }
      const project = await created.json();
      state.currentProjectId = project.id;
      writeStored(LAST_PROJECT_KEY, project.id);
    }
    closeProjectModal();
    await loadProjects();
    renderSessions();
    renderWorkspace();
    // 新项目顺手开一条空白对话，省掉一次点击
    if (!state.sessionId && state.currentProjectId) {
      newConversation(state.currentProjectId);
    }
  } catch (error) {
    setProjectHint('保存失败：' + error.message, true);
  } finally {
    el.projectSave.disabled = false;
  }
}

async function deleteProject() {
  const editor = state.projectEditor;
  if (!editor || !editor.id) {
    return;
  }
  const project = projectById(editor.id);
  const count = project ? project.sessionCount : 0;
  if (!window.confirm(`删除项目「${project ? project.name : ''}」？${count ? `\n它下面的 ${count} 个对话也会一起删除。` : ''}`)) {
    return;
  }
  try {
    const response = await apiFetch(`/api/projects/${encodeURIComponent(editor.id)}`, { method: 'DELETE' });
    if (!response.ok) {
      throw new Error(await describeError(response));
    }
    closeProjectModal();
    if (state.currentProjectId === editor.id) {
      state.currentProjectId = null;
    }
    await loadProjects();
    await loadSessions();
    const next = state.projects[0];
    if (!state.sessionId && next) {
      newConversation(next.id);
    }
  } catch (error) {
    setProjectHint('删除失败：' + error.message, true);
  }
}

function setProjectHint(text, isError = false) {
  el.projectHint.textContent = text;
  el.projectHint.className = 'settings-status' + (isError ? ' is-error' : '');
}

/* ---------- 对话内切换文件夹 ---------- */

function toggleFolderMenu() {
  if (!el.folderMenu.hidden) {
    el.folderMenu.hidden = true;
    return;
  }
  const folders = availableFolders();
  if (!folders.length) {
    showNotice('当前没有可用的文件夹');
    return;
  }
  if (folders.length === 1 && state.sessionId) {
    showNotice('这个项目只绑定了一个文件夹，可在项目设置里添加');
  }
  el.folderMenu.replaceChildren();
  const title = document.createElement('p');
  title.className = 'model-title';
  title.textContent = '对话使用的文件夹';
  el.folderMenu.append(title);
  for (const folder of folders) {
    const item = document.createElement('button');
    item.type = 'button';
    item.className = 'perm-item' + (folder === state.workspace ? ' is-on' : '');
    const label = document.createElement('span');
    label.className = 'mono';
    label.textContent = folderName(folder);
    label.title = folder;
    const sub = document.createElement('small');
    sub.textContent = folder;
    item.append(label, sub);
    item.addEventListener('click', () => {
      el.folderMenu.hidden = true;
      switchWorkspace(folder);
    });
    el.folderMenu.append(item);
  }
  el.folderMenu.hidden = false;
}

async function switchWorkspace(path) {
  if (path === state.workspace) {
    return;
  }
  if (!state.sessionId) {
    return;
  }
  try {
    const response = await apiFetch(`/api/sessions/${encodeURIComponent(state.sessionId)}/workspace`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ workspace: path })
    });
    if (!response.ok) {
      throw new Error(await describeError(response));
    }
    const session = await response.json();
    state.workspace = session.workspace || path;
    writeStored(LAST_WORKSPACE_KEY, state.workspace);
    const listed = state.sessions.find((item) => item.id === session.sessionId);
    if (listed) {
      listed.workspace = state.workspace;
    }
    renderWorkspace();
    renderSessions();
    loadTree('');
    showNotice('已切换到 ' + folderName(state.workspace) + '，下一次运行生效');
  } catch (error) {
    showNotice('切换文件夹失败：' + error.message);
  }
}

/* ---------- 健康检查 ---------- */

async function loadHealth() {
  try {
    const response = await apiFetch('/api/health');
    if (!response.ok) {
      return;
    }
    const health = await response.json();
    if (health.permissionMode) {
      applyPermissionMode(health.permissionMode);
    }
    await loadSettings();
  } catch (error) {
    console.warn('[health]', error);
  }
}

/* ---------- 权限模式 ---------- */

const PERMISSION_MODES = [
  { mode: 'READ_ONLY', label: 'READ_ONLY', hint: '只读：写入与命令一律拒绝' },
  { mode: 'ASK', label: 'ASK', hint: '逐次批准：每次写入和命令都要确认' },
  { mode: 'AUTO_EDIT', label: '自动改', hint: '改文件直接生效，命令仍需批准；改完看 diff，可回滚' },
  { mode: 'AUTO', label: 'AUTO', hint: '全部放行：写入与命令都不再打断' }
];

function applyPermissionMode(mode) {
  const value = String(mode || '').toUpperCase();
  const found = PERMISSION_MODES.find((item) => item.mode === value);
  state.permissionMode = value;
  el.permChip.textContent = found ? found.label : value;
  el.permChip.className = 'perm-chip is-' + value.toLowerCase().replace(/_/g, '');
  el.permChip.title = (found ? found.hint : value) + ' · 点击切换';
  el.permChip.hidden = false;
  renderPermissionMenu();
  renderPermissionOptions();
  if (el.aboutPermission) {
    el.aboutPermission.textContent = el.permChip.textContent;
  }
}

function togglePermissionMenu(force) {
  const open = force === undefined ? el.permMenu.hidden : force;
  el.permMenu.hidden = !open;
  el.permChip.setAttribute('aria-expanded', String(open));
  if (open) {
    renderPermissionMenu();
  }
}

function renderPermissionMenu() {
  el.permMenu.replaceChildren();
  for (const item of PERMISSION_MODES) {
    const option = document.createElement('button');
    option.type = 'button';
    option.setAttribute('role', 'menuitem');
    option.className = 'perm-option' + (item.mode === state.permissionMode ? ' is-active' : '');
    const label = document.createElement('strong');
    label.textContent = item.label;
    const hint = document.createElement('span');
    hint.textContent = item.hint;
    option.append(label, hint);
    option.addEventListener('click', () => choosePermission(item.mode));
    el.permMenu.append(option);
  }
}

/** 运行时切换：服务端立即生效，重启后回到配置文件里的初始值。 */
async function choosePermission(mode) {
  togglePermissionMenu(false);
  if (mode === state.permissionMode) {
    renderPermissionOptions();
    return;
  }
  try {
    const response = await apiFetch('/api/settings/permission-mode', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ mode })
    });
    if (!response.ok) {
      throw new Error(await describeError(response));
    }
    const data = await response.json();
    applyPermissionMode(data.permissionMode);
    const text = '权限已切换为「' + el.permChip.textContent + '」，对后续工具调用立即生效。';
    showNotice(text);
    setSettingsStatus(el.settingsPermissionHint, text, 'ok');
  } catch (error) {
    showNotice('切换权限失败：' + error.message);
    setSettingsStatus(el.settingsPermissionHint, '切换失败：' + error.message, 'error');
  }
}

/* ---------- 模型设置 ---------- */

async function loadSettings() {
  try {
    const response = await apiFetch('/api/settings');
    if (!response.ok) {
      return;
    }
    applyModelSettings(await response.json());
  } catch (error) {
    console.warn('[settings]', error);
  }
}

function applyModelSettings(data) {
  if (!data) {
    return;
  }
  state.settingsData = data;
  if (data.model) {
    el.modelChip.textContent = data.model;
    el.modelChip.hidden = false;
  }
  el.modelName.value = data.model || '';
  el.modelEndpoint.value = data.modelEndpoint || '';
  el.modelKey.value = '';
  el.modelKey.disabled = false;
  el.modelClearKey.checked = false;
  el.modelClearKey.disabled = !data.modelApiKeySet;
  state.modelApiKeySet = data.modelApiKeySet === true;
  state.modelApiKeyMasked = data.modelApiKeyMasked || '';
  setModelHint(idleModelHint(), 'idle');
  el.modelChip.title = `模型：${data.model || '未设置'} · 点击切换`;
  state.modelOptions = Array.isArray(data.modelOptions) ? data.modelOptions.slice() : [];
  renderModelOptions(data.model);
  // 设置面板里的模型区保持同一份数据，否则两个入口会各说各话
  renderSettingsModel(data);
  renderAbout();
}

/** 模型面板里的状态行：保存成功/失败直接写在这里，不只闪一下顶部提示。 */
function setModelHint(text, tone) {
  el.modelHint.textContent = text || '';
  el.modelHint.className = 'model-hint' + (tone && tone !== 'idle' ? ' is-' + tone : '');
}

function idleModelHint() {
  return state.modelApiKeySet
    ? `API Key 已配置（${state.modelApiKeyMasked || '••••'}）：留空保存会保持原密钥，勾选「清除」可移除。`
    : 'API Key 未配置：填入后即可调用真实模型。';
}

/** 勾选「清除」就禁用输入框并说明后果，避免「填了 Key 又勾清除」的歧义。 */
function onModelClearKeyToggle() {
  const clear = el.modelClearKey.checked;
  el.modelKey.disabled = clear;
  if (clear) {
    el.modelKey.value = '';
    setModelHint('保存后会删除已保存的 API Key，直到重新填写前模型调用会失败。', 'warn');
  } else {
    setModelHint(idleModelHint(), 'idle');
  }
}

/** 下拉里给出候选模型，点击即切换；当前模型排在最前面。 */
/** 候选模型列表：当前模型排第一，其余去重保序。 */
function modelCandidates(current) {
  const names = [];
  if (current) {
    names.push(current);
  }
  for (const name of state.modelOptions) {
    if (name && !names.includes(name)) {
      names.push(name);
    }
  }
  return names;
}

function renderModelOptions(current) {
  el.modelOptions.replaceChildren();
  for (const name of modelCandidates(current)) {
    const item = document.createElement('button');
    item.type = 'button';
    item.className = 'model-option' + (name === current ? ' is-active' : '');
    item.setAttribute('role', 'option');
    item.setAttribute('aria-selected', String(name === current));
    item.textContent = name;
    item.addEventListener('click', () => saveModel(name));
    el.modelOptions.append(item);
  }
}

function toggleModelMenu(force) {
  const open = force === undefined ? el.modelMenu.hidden : force;
  el.modelMenu.hidden = !open;
  el.modelChip.setAttribute('aria-expanded', String(open));
  if (open) {
    el.modelKey.value = '';
    el.modelKey.disabled = false;
    el.modelClearKey.checked = false;
    setModelHint(idleModelHint(), 'idle');
    el.modelName.focus();
  }
}

async function saveModel(override) {
  // 点下拉项时直接切换；点「保存」按钮时用输入框里的值
  await submitModel({
    model: (typeof override === 'string' ? override : el.modelName.value).trim(),
    endpoint: el.modelEndpoint.value,
    apiKey: el.modelKey.value,
    clearKey: el.modelClearKey.checked,
    hint: setModelHint,
    button: el.modelSave,
    onSaved: () => {
      // 保存成功就收起面板。结果在顶部提示里，和权限菜单点完即关一样。
      // 失败时不关，错误留在面板里，改完端点或 Key 可以再试。
      toggleModelMenu(false);
      el.modelChip.focus();
    }
  });
}

function saveModelFromSettings() {
  submitModel({
    model: el.settingsModelName.value.trim(),
    endpoint: el.settingsModelEndpoint.value,
    apiKey: el.settingsModelKey.value,
    clearKey: el.settingsModelClearKey.checked,
    hint: (text, tone) => setSettingsStatus(el.settingsModelHint, text, tone),
    button: el.settingsModelSave,
    onSaved: renderAbout
  });
}

/** 模型菜单和设置面板共用同一套保存流程，只是把提示写到各自的容器里。 */
async function submitModel(source) {
  if (!source.model) {
    source.hint('请选择或填写模型名。', 'error');
    showNotice('请选择或填写模型名。');
    return false;
  }
  const payload = buildModelUpdate({
    model: source.model,
    endpoint: source.endpoint,
    apiKey: source.apiKey,
    clearKey: source.clearKey
  });
  const keyChanged = !payload.clearApiKey && Boolean(payload.apiKey);
  source.button.disabled = true;
  source.hint('保存中…', 'idle');
  try {
    const response = await apiFetch('/api/settings/model', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });
    if (!response.ok) {
      throw new Error(await describeError(response));
    }
    const data = await response.json();
    applyModelSettings(data);
    showNotice(describeModelSaved(data, { clearKey: payload.clearApiKey, keyChanged }));
    source.onSaved?.();
    return true;
  } catch (error) {
    // 失败时面板不关：错误留在原来的位置，用户可以直接改端点/Key 再试
    source.hint('保存失败：' + error.message, 'error');
    showNotice('保存模型配置失败：' + error.message);
    return false;
  } finally {
    source.button.disabled = false;
  }
}

/* ---------- 设置面板 ---------- */

function setSettingsStatus(node, text, tone) {
  if (!node) {
    return;
  }
  node.textContent = text || '';
  node.className = 'settings-status' + (tone && tone !== 'idle' ? ' is-' + tone : '');
}

function openSettings(section) {
  el.settings.hidden = false;
  renderSettings(section);
  el.settingsClose.focus();
}

function closeSettings() {
  el.settings.hidden = true;
  el.settingsOpen.focus();
}

function renderSettings(section) {
  showSettingsSection(section || state.settingsSection || 'appearance');
  renderThemeOptions();
  renderAccentOptions();
  renderPermissionOptions();
  renderSettingsModel(state.settingsData);
  renderAbout();
}

function showSettingsSection(section) {
  state.settingsSection = section;
  for (const tab of el.settingsNav.querySelectorAll('.settings-tab')) {
    const on = tab.dataset.section === section;
    tab.classList.toggle('is-on', on);
    tab.setAttribute('aria-selected', String(on));
  }
  for (const pane of el.settings.querySelectorAll('.settings-pane')) {
    pane.hidden = pane.dataset.section !== section;
  }
}

function renderThemeOptions() {
  if (!el.themeOptions) {
    return;
  }
  el.themeOptions.replaceChildren();
  for (const item of THEME_MODES) {
    const on = state.themeMode === item.mode;
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'theme-card' + (on ? ' is-on' : '');
    button.setAttribute('role', 'radio');
    button.setAttribute('aria-checked', String(on));
    // 一张小预览：顶栏 + 内容区两块，三种主题各画各的
    const preview = document.createElement('span');
    preview.className = 'theme-preview is-' + item.mode;
    preview.append(document.createElement('span'), document.createElement('span'));
    const label = document.createElement('span');
    label.className = 'theme-card-label';
    label.textContent = item.label;
    const hint = document.createElement('span');
    hint.className = 'theme-card-hint';
    hint.textContent = item.hint;
    button.append(preview, label, hint);
    button.title = item.hint;
    button.addEventListener('click', () => applyTheme(item.mode));
    el.themeOptions.append(button);
  }
}

function renderAccentOptions() {
  if (!el.accentOptions) {
    return;
  }
  el.accentOptions.replaceChildren();
  for (const item of ACCENTS) {
    const on = state.accent === item.name;
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'accent-dot is-' + item.name + (on ? ' is-on' : '');
    button.setAttribute('role', 'radio');
    button.setAttribute('aria-checked', String(on));
    button.setAttribute('aria-label', item.label);
    button.title = item.label;
    button.addEventListener('click', () => applyAccent(item.name));
    el.accentOptions.append(button);
  }
}

function renderPermissionOptions() {
  if (!el.permissionOptions) {
    return;
  }
  el.permissionOptions.replaceChildren();
  for (const item of PERMISSION_MODES) {
    const on = state.permissionMode === item.mode;
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'perm-card' + (on ? ' is-on' : '');
    button.setAttribute('role', 'radio');
    button.setAttribute('aria-checked', String(on));
    const head = document.createElement('span');
    head.className = 'perm-card-head';
    const title = document.createElement('strong');
    title.textContent = PERMISSION_TITLES[item.mode] || item.label;
    const code = document.createElement('span');
    code.className = 'perm-card-code';
    code.textContent = item.label;
    head.append(title, code);
    const hint = document.createElement('span');
    hint.className = 'perm-card-hint';
    hint.textContent = item.hint;
    button.append(head, hint);
    button.addEventListener('click', () => choosePermission(item.mode));
    el.permissionOptions.append(button);
  }
}

function renderSettingsModel(data) {
  if (!el.settingsModelName) {
    return;
  }
  const model = (data && data.model) || el.modelName.value || '';
  el.settingsModelName.value = model;
  el.settingsModelEndpoint.value = (data && data.modelEndpoint) || el.modelEndpoint.value || '';
  el.settingsModelKey.value = '';
  el.settingsModelKey.disabled = false;
  el.settingsModelClearKey.checked = false;
  el.settingsModelClearKey.disabled = !state.modelApiKeySet;
  renderSettingsModelOptions(model);
  setSettingsStatus(el.settingsModelHint, idleModelHint(), 'idle');
}

/** 候选模型做成可点的胶囊：点一下只填进输入框，点「保存模型」才提交。 */
function renderSettingsModelOptions(current) {
  if (!el.settingsModelOptions) {
    return;
  }
  el.settingsModelOptions.replaceChildren();
  for (const name of modelCandidates(current)) {
    const chip = document.createElement('button');
    chip.type = 'button';
    chip.className = 'settings-model-chip' + (name === current ? ' is-on' : '');
    chip.textContent = name;
    chip.addEventListener('click', () => {
      el.settingsModelName.value = name;
      renderSettingsModelOptions(name);
    });
    el.settingsModelOptions.append(chip);
  }
}

function onSettingsClearKeyToggle() {
  const clear = el.settingsModelClearKey.checked;
  el.settingsModelKey.disabled = clear;
  if (clear) {
    el.settingsModelKey.value = '';
    setSettingsStatus(el.settingsModelHint, '保存后会删除已保存的 API Key，重新填写前模型调用会失败。', 'warn');
  } else {
    setSettingsStatus(el.settingsModelHint, idleModelHint(), 'idle');
  }
}

function renderAbout() {
  if (!el.aboutWorkspace) {
    return;
  }
  el.aboutWorkspace.textContent = state.workspace || '未选择';
  el.aboutWorkspace.title = state.workspace || '';
  el.aboutConnection.textContent = el.connectionText.textContent || '未连接';
  el.aboutModel.textContent = (state.settingsData && state.settingsData.model) || '未设置';
  el.aboutPermission.textContent = el.permChip.textContent || '-';
}

/* ---------- 附件 ---------- */

const MAX_ATTACHMENTS = 5;
const MAX_ATTACHMENT_BYTES = 2 * 1024 * 1024;

/** 从系统拖文件进来。和粘贴走同一条附件队列，限制也一样：最多 5 个、单个 2 MB。 */
function bindFileDrop() {
  let depth = 0;
  document.addEventListener('dragenter', (event) => {
    if (!dragHasFiles(event)) {
      return;
    }
    event.preventDefault();
    depth += 1;
    el.dropVeil.hidden = false;
  });
  document.addEventListener('dragover', (event) => {
    if (!dragHasFiles(event)) {
      return;
    }
    // 不拦这个，浏览器会把松开当成「打开文件」
    event.preventDefault();
    if (event.dataTransfer) {
      event.dataTransfer.dropEffect = 'copy';
    }
  });
  document.addEventListener('dragleave', (event) => {
    if (!dragHasFiles(event)) {
      return;
    }
    depth = Math.max(0, depth - 1);
    if (depth === 0 || !event.relatedTarget) {
      depth = 0;
      el.dropVeil.hidden = true;
    }
  });
  document.addEventListener('drop', (event) => {
    if (!dragHasFiles(event)) {
      return;
    }
    event.preventDefault();
    depth = 0;
    el.dropVeil.hidden = true;
    const files = filesFromDrop(event.dataTransfer);
    if (files.length) {
      addAttachments(files);
    }
  });
}

function dragHasFiles(event) {
  const types = event.dataTransfer && event.dataTransfer.types;
  return !!types && [...types].includes('Files');
}

/** 文件夹不展开。左侧工作区已经能打开目录，这里只收具体文件。 */
function filesFromDrop(dataTransfer) {
  const files = [];
  let sawDirectory = false;
  const items = [...(dataTransfer.items || [])];
  if (items.length) {
    for (const item of items) {
      if (item.kind !== 'file') {
        continue;
      }
      const entry = item.webkitGetAsEntry && item.webkitGetAsEntry();
      if (entry && entry.isDirectory) {
        sawDirectory = true;
        continue;
      }
      const file = item.getAsFile();
      if (file) {
        files.push(file);
      }
    }
  } else {
    files.push(...(dataTransfer.files || []));
  }
  if (sawDirectory) {
    showNotice('文件夹先不收。把里面的文件拖进来，或用左侧工作区打开目录。');
  }
  return files;
}

/** 粘贴时优先取剪贴板里的文件（截图、复制的文件），纯文本粘贴保持原样。 */
function onPasteAttachments(event) {
  const data = event.clipboardData;
  if (!data) {
    return;
  }
  const files = [...(data.files || [])];
  if (!files.length) {
    for (const item of data.items || []) {
      if (item.kind === 'file') {
        const file = item.getAsFile();
        if (file) {
          files.push(file);
        }
      }
    }
  }
  if (!files.length) {
    return;
  }
  event.preventDefault();
  addAttachments(files);
}

async function addAttachments(files) {
  for (const file of files) {
    if (state.attachments.length >= MAX_ATTACHMENTS) {
      showNotice(`一次最多附加 ${MAX_ATTACHMENTS} 个文件。`);
      break;
    }
    const name = file.name || `paste-${Date.now()}.png`;
    if (file.size > MAX_ATTACHMENT_BYTES) {
      showNotice(`「${name}」超过 2 MB，已跳过。`);
      continue;
    }
    const mimeType = file.type || guessMime(name);
    try {
      state.attachments.push({ name, mimeType, dataBase64: await readAsDataUrl(file) });
    } catch (error) {
      showNotice('读取附件失败：' + error.message);
    }
  }
  renderAttachments();
  syncComposer();
}

function setAttachments(list) {
  state.attachments = (list || []).slice();
  renderAttachments();
  syncComposer();
}

function renderAttachments() {
  el.attachments.replaceChildren();
  el.attachments.hidden = state.attachments.length === 0;
  state.attachments.forEach((item, index) => {
    const chip = document.createElement('span');
    chip.className = 'attachment-chip';
    const name = document.createElement('span');
    name.className = 'attachment-name';
    name.textContent = item.name;
    const remove = document.createElement('button');
    remove.type = 'button';
    remove.className = 'attachment-remove';
    remove.title = '移除附件';
    remove.textContent = '×';
    remove.addEventListener('click', () => {
      state.attachments.splice(index, 1);
      renderAttachments();
      syncComposer();
    });
    chip.append(name, remove);
    el.attachments.append(chip);
  });
}

function attachmentSummary(list) {
  const names = (list || []).map((item) => item.name).join('、');
  return names ? `（附带文件：${names}）` : '（附件）';
}

function readAsDataUrl(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result || ''));
    reader.onerror = () => reject(new Error('读取文件失败'));
    reader.readAsDataURL(file);
  });
}

function guessMime(name) {
  const lower = String(name || '').toLowerCase();
  if (lower.endsWith('.png')) return 'image/png';
  if (lower.endsWith('.jpg') || lower.endsWith('.jpeg')) return 'image/jpeg';
  if (lower.endsWith('.webp')) return 'image/webp';
  if (lower.endsWith('.gif')) return 'image/gif';
  if (lower.endsWith('.json')) return 'application/json';
  // 类型未知时交给服务端嗅探：文本内联，二进制落盘后只给路径
  return 'application/octet-stream';
}

/* ---------- 会话 ---------- */

/**
 * 选完目录之后的入口：目录属于某个项目就在那个项目下开草稿，
 * 不属于任何项目就顺手建一个新项目并把这个目录加进去。
 */
async function startSession(workspace) {
  const target = workspace || state.workspace || localStorage.getItem(LAST_WORKSPACE_KEY);
  if (!target) {
    openPicker({ firstRun: true });
    return;
  }
  writeStored(LAST_WORKSPACE_KEY, target);
  const project = state.projects.find((item) => item.folders.some((folder) => samePath(folder, target)));
  if (project) {
    const chosen = project.folders.find((folder) => samePath(folder, target));
    newConversation(project.id, { workspace: chosen });
    return;
  }
  // 不属于任何项目：把这次选中的目录直接变成一个项目，少一步确认
  state.projectEditor = { id: null, name: folderName(target) || '新项目', folders: [target] };
  el.projectModalTitle.textContent = '新建项目';
  el.projectName.value = state.projectEditor.name;
  el.projectDelete.hidden = true;
  el.projectHint.textContent = '这个目录还没有归属项目，确认后会在它下面开一个新对话。';
  el.projectHint.className = 'settings-status';
  renderProjectFolders();
  el.projectModal.hidden = false;
  el.projectName.focus();
}



async function activate(sessionId, title, workspace) {
  closeStream();
  state.reconnectAttempt = 0;
  stopRunClock();
  state.sessionId = sessionId;
  resetCommandPanel();
  state.task = null;
  state.taskDirty = false;
  state.taskBusy = false;
  state.pausing = false;
  state.steering = false;
  el.taskDraftConstraints.value = '';
  el.taskDraftAcceptance.value = '';
  renderTaskContract();
  const listed = state.sessions.find((item) => item.id === sessionId);
  if (listed && listed.projectId) {
    state.currentProjectId = listed.projectId;
    writeStored(LAST_PROJECT_KEY, listed.projectId);
  }
  // 换了会话，@ 补全的文件索引必须重取，否则会短暂列出上一个工作区的文件
  state.fileIndex = [];
  state.fileIndexSession = null;
  state.fileIndexFailedAt = 0;
  loadInputHistory(sessionId);
  state.sessionTitle = title || '新任务';
  state.workspace = workspace || state.workspace;
  state.pendingInputs = [];
  localStorage.setItem(LAST_SESSION_KEY, sessionId);
  el.crumbTitle.textContent = state.sessionTitle;
  el.sessionChip.textContent = sessionId.slice(0, 8);
  el.sessionChip.title = sessionId + '\n点击复制会话 ID';
  el.sessionChip.hidden = false;
  renderWorkspace();
  renderSessions();
  setConnection('pending', '连接中');
  // 先问服务端这个会话是否还在跑，再回放事件：回放不会覆盖掉「运行中」，
  // 而已经结束的运行会在回放里收到终态事件，把状态纠正回来。
  // 先按页拉取尾部历史（长会话不再一次性回放上千条事件），再从游标接上实时流
  state.historyHasMore = false;
  state.windowEvents = [];
  await loadHistory();
  if (state.sessionId !== sessionId) {
    return;
  }
  await calibrateRun(sessionId);
  if (state.sessionId !== sessionId) {
    return;
  }
  connect(state.cursor);
  setComposerEnabled(true);
  state.previewPath = null;
  el.previewPath.textContent = '未选择文件';
  el.previewBody.textContent = '在左侧文件树里点一个文件，或从「变更」里点开 diff 文件。';
  loadTree('');
  syncCommandPolling();
}

/** 断线/刷新后校准运行状态：服务端还有活跃 run 就恢复计时与停止按钮。 */
async function calibrateRun(sessionId) {
  const wasRunning = state.running;
  state.running = false;
  state.runId = null;
  try {
    const response = await apiFetch(`/api/sessions/${encodeURIComponent(sessionId)}/runs/active`);
    if (!response.ok) {
      return;
    }
    const data = await response.json();
    if (!data.runId || state.sessionId !== sessionId) {
      // 服务端已经没有活跃 run：清掉回放残留的「正在处理」气泡，否则界面会一直显示在跑
      clearThinking();
      if (wasRunning) {
        stopRunClock();
        finishReply(false);
        setRunState('idle', '空闲');
      }
      syncComposer();
      return;
    }
    state.running = true;
    state.runId = data.runId;
    state.lastRunId = data.runId;
    startRunClock(Date.now());
    showThinking();
    setComposerEnabled(false);
    markSessionStatus('RUNNING');
  } catch (error) {
    console.warn('[active]', error);
  }
}

function renderWorkspace() {
  if (!state.workspace) {
    el.workspaceChip.hidden = true;
    el.workspaceName.textContent = 'workspace';
    return;
  }
  const project = currentProject();
  el.workspaceName.textContent = project ? `${project.name} / ${folderName(state.workspace)}` : folderName(state.workspace);
  el.workspaceName.title = (project
    ? `项目：${project.name}\n当前文件夹：${state.workspace}\n点击切换`
    : state.workspace + '\n点击选择其他目录');
  el.workspaceChip.title = state.workspace + '\n点击选择其他目录（会新建对话）';
  el.workspaceChip.hidden = false;
}

function folderName(path) {
  if (!path) {
    return '';
  }
  const parts = String(path).split(/[\\/]+/).filter(Boolean);
  return parts.length ? parts[parts.length - 1] : path;
}

function renameSessionIfDefault(content) {
  if (state.sessionTitle !== '新任务') {
    return;
  }
  const title = preview(content, 22);
  state.sessionTitle = title;
  const found = state.sessions.find((item) => item.id === state.sessionId);
  if (found) {
    found.title = title;
  }
  el.crumbTitle.textContent = title;
  renderSessions();
}

function activateSession(sessionId) {
  const found = state.sessions.find((item) => item.id === sessionId);
  if (!found || sessionId === state.sessionId) {
    closeSidebar();
    return;
  }
  resetView();
  setRunState('idle', '空闲');
  activate(sessionId, found.title, found.workspace);
  closeSidebar();
}

/** 侧栏按项目分组渲染：项目头 + 空白对话（置顶）+ 历史对话 + 归档折叠区。 */
function renderSessions() {
  el.sessionList.replaceChildren();
  const groups = groupSessionsByProject(state.projects, state.sessions);
  for (const group of groups) {
    el.sessionList.append(projectGroupNode(group));
  }
  el.sessionEmpty.hidden = groups.length > 0;
  // 顶部数字是项目总数，对话数已经由每个项目自己的徽章表达
  el.sessionCount.textContent = String(state.projects.length);
}

function projectGroupNode({ project, blank, active, archived }) {
  const item = document.createElement('li');
  item.className = 'project-group';

  const key = project.id || UNGROUPED_KEY;
  const collapsed = collapsedProjects.has(key);

  const head = document.createElement('div');
  head.className = 'project-head';

  // 折叠箭头固定在最左，点箭头或项目名都能收起整个分组
  const toggle = document.createElement('button');
  toggle.type = 'button';
  toggle.className = 'project-toggle' + (collapsed ? ' is-collapsed' : '');
  toggle.title = (collapsed ? '展开「' : '收起「') + project.name + '」';
  toggle.setAttribute('aria-label', toggle.title);
  toggle.setAttribute('aria-expanded', String(!collapsed));
  toggle.innerHTML = '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M6 3.5 10 8l-4 4.5" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>';
  toggle.addEventListener('click', () => toggleProject(key));
  head.append(toggle);

  const name = document.createElement('button');
  name.type = 'button';
  name.className = 'project-name';
  name.title = project.folders && project.folders.length ? project.folders.join('\n') : project.name;
  const label = document.createElement('span');
  label.className = 'project-label';
  label.textContent = project.name;
  const count = document.createElement('span');
  count.className = 'project-count';
  count.textContent = String(active.length + (blank ? 1 : 0));
  name.append(label, count);
  name.addEventListener('click', () => toggleProject(key));
  head.append(name);

  // 新建对话 / 项目设置默认弱化，hover 或键盘聚焦时才出现
  if (project.id) {
    const actions = document.createElement('div');
    actions.className = 'project-actions';

    const add = document.createElement('button');
    add.type = 'button';
    add.className = 'icon-btn icon-btn-sm';
    add.title = '在此项目新建对话';
    add.setAttribute('aria-label', '在此项目新建对话');
    add.innerHTML = '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M8 3v10M3 8h10" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>';
    add.addEventListener('click', (event) => {
      event.stopPropagation();
      newConversation(project.id);
    });

    const settings = document.createElement('button');
    settings.type = 'button';
    settings.className = 'icon-btn icon-btn-sm';
    settings.title = '项目设置';
    settings.setAttribute('aria-label', '项目设置');
    settings.textContent = '⋯';
    settings.addEventListener('click', (event) => {
      event.stopPropagation();
      openProjectModal(project.id);
    });

    actions.append(add, settings);
    head.append(actions);
  }
  item.append(head);

  const list = document.createElement('ul');
  list.className = 'project-sessions';
  list.hidden = collapsed;

  // 空白对话永远置顶，用加号图标区分于历史对话
  if (blank) {
    list.append(sessionItemNode(blank));
  }
  for (const session of active) {
    list.append(sessionItemNode(session));
  }
  if (!list.children.length) {
    const empty = document.createElement('li');
    empty.className = 'project-empty';
    empty.textContent = project.id ? '还没有对话' : '没有未分组的对话';
    list.append(empty);
  }
  item.append(list);
  if (archived.length) {
    item.append(archivedGroupNode(archived));
  }
  return item;
}

/** 归档区：默认收起，点开才显示，不占主列表的注意力。 */
function archivedGroupNode(sessions) {
  const details = document.createElement('details');
  details.className = 'project-archived';
  const summary = document.createElement('summary');
  summary.textContent = `已归档 ${sessions.length}`;
  const list = document.createElement('ul');
  list.className = 'project-sessions';
  for (const session of sessions) {
    list.append(sessionItemNode(session));
  }
  details.append(summary, list);
  return details;
}

/** 状态圆点：运行中黄、完成绿、失败红；未知状态保持中性灰。 */
function sessionStatusClass(status) {
  const value = String(status || '').trim().toLowerCase();
  return value ? ' is-' + value : ' is-idle';
}

function sessionItemNode(session) {
  const item = document.createElement('li');
  item.className = 'session-row';
  const button = document.createElement('button');
  button.type = 'button';
  const blank = session.isBlank === true;
  button.className = 'session-item'
    + (session.id === state.sessionId ? ' is-active' : '')
    + (blank ? ' is-blank' : '')
    + (session.archived ? ' is-archived' : '');
  button.title = blank
    ? '新对话：还没有消息，发送第一条后自动变成历史对话'
    : (session.workspace ? `${session.title}\n${session.workspace}` : session.title);
  button.addEventListener('click', () => activateSession(session.id));

  const text = document.createElement('span');
  text.className = 'session-title';
  text.textContent = blank ? '新对话' : session.title;

  if (blank) {
    // 空白对话不画状态圆点，用加号图标表示「可以立刻开始」
    const icon = document.createElement('span');
    icon.className = 'session-new-icon';
    icon.setAttribute('aria-hidden', 'true');
    icon.innerHTML = '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M8 3.5v9M3.5 8h9" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>';
    button.append(icon);
  } else {
    const dot = document.createElement('span');
    dot.className = 'dot' + sessionStatusClass(session.status);
    dot.setAttribute('aria-hidden', 'true');
    button.append(dot);
  }
  button.append(text);

  const action = document.createElement('button');
  action.type = 'button';
  action.className = 'session-action';
  action.title = session.archived ? '恢复到列表' : '归档（保留历史，从列表里收起）';
  action.setAttribute('aria-label', action.title);
  action.textContent = session.archived ? '↩' : '↓';
  action.addEventListener('click', (event) => {
    event.stopPropagation();
    toggleArchive(session);
  });

  item.append(button, action);
  return item;
}

/** 归档 / 恢复：只是从主列表收起，事件与对话记忆都保留。 */
async function toggleArchive(session) {
  try {
    const response = await apiFetch(`/api/sessions/${encodeURIComponent(session.id)}/archive`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ archived: !session.archived })
    });
    if (!response.ok) {
      throw new Error(await describeError(response));
    }
    await Promise.all([loadSessions(), loadProjects()]);
    showNotice(session.archived ? '已恢复到列表' : '已归档，历史仍然保留');
  } catch (error) {
    showNotice('操作失败：' + error.message);
  }
}

async function copySessionId() {  if (!state.sessionId) {
    return;
  }
  try {
    await navigator.clipboard.writeText(state.sessionId);
    showNotice('已复制会话 ID：' + state.sessionId);
    setTimeout(hideNotice, 2200);
  } catch (error) {
    console.warn('[clipboard]', error);
  }
}

/* ---------- 历史分页 ---------- */

/**
 * 只渲染尾部一页历史，再挂上实时流。
 * 长会话（几百上千条事件）不再在首屏逐条建 DOM，这是刷新慢的主要原因。
 */
async function loadHistory({ keepViewport = false } = {}) {
  if (!state.sessionId) {
    return;
  }
  const sessionId = state.sessionId;
  let data;
  try {
    const response = await apiFetch(
      `/api/sessions/${encodeURIComponent(sessionId)}/history?limit=${state.historyPageSize}`);
    if (!response.ok) {
      throw new Error(await describeError(response));
    }
    data = await response.json();
  } catch (error) {
    showNotice('读取历史失败：' + error.message);
    return;
  }
  if (state.sessionId !== sessionId) {
    return;
  }
  renderHistoryWindow(data.events || [], data.hasMore === true, { keepViewport });
  await loadPlan();
  await loadQueue();
  await loadTaskContract();
}

/**
 * 用给定的事件窗口重建消息区。
 *
 * <p>重建是同步的：翻页请求返回后到重放结束之间不会有实时事件插进来，
 * 所以「旧页面 + 新一页」拼出来的顺序与游标都是连续的。
 */
function renderHistoryWindow(events, hasMore, { keepViewport = false } = {}) {
  const anchor = keepViewport ? el.messages.scrollHeight - el.messages.scrollTop : 0;
  resetView();
  state.pendingInputs = [];
  state.queue = [];
  state.queueEdit = null;
  renderQueue();
  state.historyHasMore = hasMore;
  state.windowEvents = [];
  state.replaying = true;
  for (const event of events) {
    handleEvent(event);
  }
  state.replaying = false;
  if (state.reply && !state.running) {
    finishReply(false);
  }
  renderHistoryMore();
  if (keepViewport) {
    el.messages.scrollTop = Math.max(0, el.messages.scrollHeight - anchor);
  } else {
    scrollThread();
  }
}

/** 向前翻一页：只请求 before 之前的一页，再与当前窗口合并。 */
async function loadEarlier() {
  if (!state.sessionId || !state.windowEvents.length) {
    return;
  }
  const before = state.windowEvents[0].sequence;
  if (typeof before !== 'number') {
    return;
  }
  const sessionId = state.sessionId;
  let data;
  try {
    const response = await apiFetch(
      `/api/sessions/${encodeURIComponent(sessionId)}/history?limit=${state.historyPageSize}&before=${before}`);
    if (!response.ok) {
      throw new Error(await describeError(response));
    }
    data = await response.json();
  } catch (error) {
    showNotice('加载更早的消息失败：' + error.message);
    return;
  }
  if (state.sessionId !== sessionId) {
    return;
  }
  const older = data.events || [];
  if (!older.length) {
    state.historyHasMore = false;
    renderHistoryMore();
    return;
  }
  // 只把这一页并到窗口前面：请求量是 O(一页)，不再是 O(已加载总数)
  renderHistoryWindow([...older, ...state.windowEvents], data.hasMore === true, { keepViewport: true });
}

function renderHistoryMore() {
  const existing = el.messages.querySelector('.history-more');
  if (existing) {
    existing.remove();
  }
  if (!state.historyHasMore) {
    return;
  }
  const more = document.createElement('button');
  more.type = 'button';
  more.className = 'history-more';
  more.textContent = '加载更早的消息';
  more.addEventListener('click', async () => {
    more.disabled = true;
    more.textContent = '加载中…';
    await loadEarlier();
  });
  el.messages.prepend(more);
}

/* ---------- 事件流 ---------- */

const RECONNECT_BASE_MS = 800;
const RECONNECT_MAX_MS = 15000;

function connect(cursor) {
  const from = Number.isFinite(cursor) ? cursor : 0;
  const source = new EventSource(`/api/sessions/${encodeURIComponent(state.sessionId)}/events?after=${from}`);
  state.source = source;
  for (const type of EVENT_TYPES) {
    source.addEventListener(type, (message) => {
      try {
        handleEvent(JSON.parse(message.data));
      } catch (error) {
        console.warn('[sse] 事件解析失败', error);
      }
    });
  }
  const session = state.sessionId;
  source.onopen = () => {
    state.reconnectAttempt = 0;
    setConnection('ok', '已连接');
  };
  source.onerror = () => {
    // 接管自动重连：带着已渲染的游标续订，避免从 0 重新回放
    setConnection('error', '重连中');
    closeStream();
    scheduleReconnect(session);
  };
}

/**
 * 指数退避 + 抖动：服务端长时间不可用时，1.5 秒死循环重连会给本机日志和线程
 * 带来无意义的压力，也让用户看不出「已经重试了多久」。上限 15 秒，重连成功即归零。
 */
function scheduleReconnect(session) {
  if (state.reconnectTimer) {
    return;
  }
  const attempt = state.reconnectAttempt;
  state.reconnectAttempt = attempt + 1;
  const base = Math.min(RECONNECT_MAX_MS, RECONNECT_BASE_MS * 2 ** Math.min(attempt, 5));
  const delay = base + Math.round(base * 0.2 * Math.random());
  state.reconnectTimer = setTimeout(() => {
    state.reconnectTimer = null;
    if (state.sessionId !== session) {
      return;
    }
    setConnection('pending', attempt > 0 ? `重连中（第 ${attempt + 1} 次）` : '重连中');
    connect(state.cursor);
  }, delay);
}

function reconnect() {
  if (!state.sessionId) {
    return;
  }
  clearReconnectTimer();
  state.reconnectAttempt = 0;
  closeStream();
  setConnection('pending', '重连中');
  connect(state.cursor);
}

function clearReconnectTimer() {
  if (state.reconnectTimer) {
    clearTimeout(state.reconnectTimer);
    state.reconnectTimer = null;
  }
}

function closeStream() {
  clearReconnectTimer();
  if (state.source) {
    state.source.close();
    state.source = null;
  }
}

function handleEvent(event) {
  // 预览沿用上一条已落库事件的序号，不能参与游标，否则会被当成重复事件丢掉
  if (event && event.type === 'assistant.preview') {
    showLivePreview(event.payload && event.payload.text);
    return;
  }
  // 同一事件可能被旧连接重复推送，按 sequence 去重；规则本身在 lib/events.js 里单测
  const rule = applyRule(event, state.cursor);
  if (!rule.apply) {
    return;
  }
  if (rule.cursor !== state.cursor) {
    state.cursor = rule.cursor;
    // 实时事件也要进窗口，否则向前翻页重建时会丢掉这一段
    if (!state.replaying) {
      state.windowEvents.push(event);
    }
  }
  state.lastEventAt = Date.now();
  hideEmptyState();

  const stick = state.replaying ? false : isNearBottom(el.messages);
  const stickTimeline = state.replaying ? false : isNearBottom(el.timeline);

  const handler = EVENT_HANDLERS[event.type];
  if (handler) {
    handler(event);
  }

  if (stick) {
    scrollThread();
  }
  if (stickTimeline) {
    el.timeline.scrollTop = el.timeline.scrollHeight;
  }
  if (state.filter !== 'all') {
    applyFilter();
  }
  if (!state.replaying && ['run.started', 'run.steered', 'run.continued', 'run.completed', 'run.failed', 'run.cancelled'].includes(event.type)) {
    scheduleTaskRefresh();
  }
}

/* ---------- 事件分发 ---------- */

/**
 * 事件类型 → 处理函数。
 *
 * <p>原来是一个 380 行的 switch，读起来要在「去重/滚动/渲染」之间来回跳；
 * 拆成一事件一函数之后，加新事件只要补一条映射，改动范围可控。
 */
const EVENT_HANDLERS = {
  'run.started': onRunStarted,
  'assistant.delta': onAssistantDelta,
  'tool.started': onToolStarted,
  'tool.completed': onToolCompleted,
  'approval.required': onApprovalRequired,
  'approval.resolved': onApprovalResolved,
  'run.completed': onRunCompleted,
  'run.cancelled': onRunCancelled,
  'run.failed': onRunFailed,
  'context.compacted': onContextCompacted,
  'plan.updated': onPlanUpdated,
  'run.steered': onRunSteered,
  'run.retrying': onRunRetrying,
  'run.continued': onRunContinued,
  'subagent.started': onSubagentStarted,
  'subagent.finished': onSubagentFinished,
  'task.updated': onTaskUpdated,
  'sandbox.diff': onSandboxDiff,
  'arbitration.required': onArbitrationRequired,
  'arbitration.resolved': onArbitrationResolved
};

/** 一轮开始：接管运行状态，画用户消息，另起一个操作外壳。 */
function onRunStarted(event) {
  state.running = true;
  state.runId = event.runId || state.runId;
  state.turnRuns = event.runId ? [event.runId] : [];
  startRunClock(Date.now());
  markSessionStatus('RUNNING');
  syncComposer();
  clearThinking();
  const input = event.payload.input || '';
  if (state.pendingInputs[0] === input) {
    state.pendingInputs.shift();
    stampLastUserMessage(event.sequence, input);
  } else if (state.queue[0] === input) {
    // 排队的下一句被自动开成新一轮，从队列拿掉，再画进对话
    state.queue.shift();
    renderQueue();
    appendUserMessage(input, event.sequence);
  } else if (input) {
    appendUserMessage(input, event.sequence);
  }
  // 新的一轮另起一组，避免操作和结论接在上一轮后面
  beginTurnShell();
  ensureTurnShell();
  showThinking();
  state.modelRetryUntil = 0;
  record(event, { kind: 'run', tone: 'run', status: '开始', title: '运行开始', detail: input });
  promoteBlankSession(input);
}

/**
 * 空白对话发出第一条消息后转正：清掉空白标记、用第一句话当标题，并落库。
 *
 * <p>后端在 publish(run.started) 时已经清了 is_blank，这里同步界面状态，
 * 否则侧栏会一直把它画成「新对话」（浅色、置顶），看起来像还能被归档。
 */
function promoteBlankSession(input) {
  const found = state.sessions.find((session) => session.id === state.sessionId);
  if (!found || !found.isBlank) {
    return;
  }
  found.isBlank = false;
  const title = preview(input || '', 22) || found.title;
  found.title = title;
  state.sessionTitle = title;
  el.crumbTitle.textContent = title;
  renderSessions();
  // 标题落库：刷新后仍然是这句话，而不是又变回「新对话」
  apiFetch(`/api/sessions/${encodeURIComponent(found.id)}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title })
  }).then(() => loadProjects()).catch((error) => console.warn('[blank] 标题保存失败', error));
}

function onAssistantDelta(event) {
  commitReply(event.payload.text);
  recordDelta(event, event.payload.text);
}

function onToolStarted(event) {
  clearThinking();
  const row = addToolRow(event.payload.callId, event.payload.name, event.payload.summary);
  row.setStatus('run', '…');
  state.toolRows.set(event.payload.callId, row);
  showThinking();
  record(event, {
    kind: 'tool',
    tone: 'tool',
    status: '运行中',
    title: `${event.payload.name} · ${event.payload.summary}`,
    detail: null
  });
}

function onToolCompleted(event) {
  const failed = String(event.payload.resultSummary || '').startsWith('ERROR');
  const row = state.toolRows.get(event.payload.callId);
  if (row) {
    state.toolRows.delete(event.payload.callId);
    row.setStatus(failed ? 'error' : 'ok', failed ? '✕' : '✓');
    row.setMeta(`${event.payload.latencyMs} ms`);
    row.setBody(event.payload.resultSummary);
  }
  record(event, {
    kind: 'tool',
    tone: failed ? 'error' : 'ok',
    status: failed ? '失败' : '完成',
    title: event.payload.callId,
    detail: event.payload.resultSummary,
    foot: `${event.payload.latencyMs} ms`
  });
  if (!state.replaying) refreshCommandTasks(commandPanelVisible());
}

function onApprovalRequired(event) {
  clearThinking();
  const row = addToolRow(event.payload.callId, event.payload.name, event.payload.argsSummary);
  row.setStatus('wait', '!');
  state.toolRows.set(event.payload.callId, row);
  state.pendingApprovals.set(event.payload.callId, {
    name: event.payload.name,
    args: event.payload.argsSummary,
    row
  });
  renderApprovalDock();
  record(event, {
    kind: 'approval',
    tone: 'warn',
    status: '待审批',
    title: `${event.payload.name} · ${event.payload.argsSummary}`,
    detail: '需要你批准后才能执行',
    actions: true,
    callId: event.payload.callId
  });
}

function onApprovalResolved(event) {
  const approved = event.payload.decision === 'APPROVED';
  const pending = state.pendingApprovals.get(event.payload.callId);
  if (pending) {
    state.pendingApprovals.delete(event.payload.callId);
    pending.row.setStatus(approved ? 'run' : 'error', approved ? '…' : '✕');
    pending.row.setMeta(approved ? '已批准' : '已拒绝');
    if (!approved) {
      state.toolRows.delete(event.payload.callId);
    }
  }
  renderApprovalDock();
  record(event, {
    kind: 'approval',
    tone: approved ? 'ok' : 'error',
    status: approved ? '已批准' : '已拒绝',
    title: event.payload.callId,
    detail: null
  });
}

/** 一轮结束：「运行结束」≠「任务做完」，计划还有未完成项时如实标成未完成。 */
function onRunCompleted(event) {
  state.running = false;
  state.modelRetryUntil = 0;
  stopRunClock();
  markSessionStatus('COMPLETED');
  state.lastRunId = event.runId || state.runId || state.lastRunId;
  state.runId = null;
  state.changedFiles = event.payload.changedFiles || [];
  const openSteps = Array.isArray(event.payload.openSteps) ? event.payload.openSteps : [];
  const planTotal = Number(event.payload.planTotal || 0);
  const planDone = Number(event.payload.planDone || 0);
  const planOpen = openSteps.length > 0;
  const progress = planProgressLabel(planDone, planTotal);
  setRunState(planOpen ? 'warn' : 'done', (planOpen ? '本轮结束' : '已完成') + progress);
  setComposerEnabled(true);
  clearThinking();
  finishReply(false);
  const extras = [];
  if (event.payload.changedFiles && event.payload.changedFiles.length) {
    extras.push('变更：' + event.payload.changedFiles.join('、'));
  }
  if (event.payload.checks && event.payload.checks.length) {
    extras.push('检查：' + event.payload.checks.join('、'));
  }
  if (planOpen) {
    extras.push('剩余：' + openSteps.join('、'));
  }
  record(event, {
    kind: 'run',
    tone: planOpen ? 'warn' : 'ok',
    status: planOpen ? '未完成' : '完成',
    title: (planOpen ? '本轮结束' : '运行完成') + progress,
    detail: event.payload.summary,
    foot: extras.join(' · '),
    resumable: planOpen
  });
  appendTurnResult({
    tone: planOpen ? 'warn' : 'ok',
    title: (planOpen ? `本轮结束 · 还有 ${openSteps.length} 项没做` : '本轮已完成') + progress,
    openSteps,
    changedFiles: event.payload.changedFiles || [],
    // 自动继续会换 runId，同一轮里写过的文件分散在多个快照中，合并后再取
    runIds: state.turnRuns.length ? state.turnRuns.slice()
      : (event.runId ? [event.runId] : [state.lastRunId]),
    resumable: planOpen
  });
  refreshChanges();
}

function onRunCancelled(event) {
  state.running = false;
  state.modelRetryUntil = 0;
  stopRunClock();
  markSessionStatus('CANCELLED');
  state.lastRunId = event.runId || state.runId || state.lastRunId;
  state.runId = null;
  setRunState('idle', '已取消');
  setComposerEnabled(true);
  clearThinking();
  finishReply(false);
  record(event, {
    kind: 'run',
    tone: 'warn',
    status: '已取消',
    title: '运行已取消',
    detail: event.payload.reason || ''
  });
}

/**
 * 运行失败：取消、步数触顶、服务重启中断、真失败四种语义分开处理。
 * 前三种都能继续跑，界面上不能显示成红色错误。
 */
function onRunFailed(event) {
  state.modelRetryUntil = 0;
  const code = String(event.payload.code || '');
  // 启动失败时服务端保留消息；恢复已被 run.started 从界面移除的队列项。
  if (!state.replaying && code === 'QUEUE_START_FAILED') {
    loadQueue();
  }
  const { cancelled, paused, interrupted, resumable } = classifyRunFailure(code);
  state.running = false;
  stopRunClock();
  markSessionStatus(cancelled ? 'CANCELLED' : paused ? 'PAUSED' : interrupted ? 'INTERRUPTED' : 'FAILED');
  state.lastRunId = event.runId || state.runId || state.lastRunId;
  state.runId = null;
  setRunState(resumable ? 'warn' : cancelled ? 'idle' : 'error',
    paused ? '已暂停' : interrupted ? '已中断' : cancelled ? '已取消' : '失败');
  setComposerEnabled(true);
  clearThinking();
  finishReply(!resumable);
  record(event, {
    kind: 'run',
    tone: resumable || cancelled ? 'warn' : 'error',
    status: paused ? '可继续' : interrupted ? '已中断' : cancelled ? '已取消' : (event.payload.retryable ? '可重试' : '失败'),
    title: paused ? `本轮已暂停 · ${code}` : interrupted ? `运行已中断 · ${code}` : `运行${cancelled ? '取消' : '失败'} · ${code}`,
    detail: event.payload.message,
    resumable
  });
  // 模型错误：把原始报错翻译成可行动的提示（401 去填 Key、404 查端点…）
  const modelError = code === 'MODEL_ERROR' ? classifyModelError(event.payload.message) : null;
  if (modelError && modelError.hint) {
    showNotice(modelError.hint);
  }
  appendTurnResult({
    tone: cancelled || resumable ? 'warn' : 'error',
    title: paused ? '本轮已暂停' : interrupted ? '运行已中断' : cancelled ? '本轮已取消' : '运行失败',
    detail: event.payload.message,
    hint: modelError ? modelError.hint : '',
    actionLabel: modelError && modelError.action === 'model-settings' ? '打开模型设置' : '',
    onAction: (clickEvent) => {
      // 阻止冒泡：document 上的「点外面就收起」会立刻把刚打开的面板关掉
      clickEvent?.stopPropagation();
      toggleModelMenu(true);
      el.modelKey.focus();
    },
    resumable
  });
}

function onContextCompacted(event) {
  appendCompaction(event.payload);
}

function onPlanUpdated(event) {
  renderPlan(event.payload);
}

function onRunSteered(event) {
  const steered = event.payload.input || '';
  if (steered) {
    appendUserMessage('【纠正当前任务】' + steered, undefined, { allowTaskActions: false });
  }
  showNotice('纠正已应用：' + steered);
  setRunState('running', '已收到补充指令');
}

function onRunRetrying(event) {
  rollbackLivePreview();
  const delayMs = Number(event.payload.delayMs || 0);
  const attempt = Number(event.payload.attempt || 1);
  const seconds = Math.max(1, Math.round(delayMs / 1000));
  state.modelRetryUntil = Date.now() + delayMs;
  const reason = event.payload.reason || '模型请求失败';
  showNotice(`模型暂时连不上，${seconds} 秒后再试（第 ${attempt} 次）。可以点停止。`);
  setRunState('warn', `等待重试 · ${seconds}s`);
  record(event, {
    kind: 'run',
    tone: 'warn',
    status: `${seconds}s`,
    title: `模型请求失败，第 ${attempt} 次重试`,
    detail: reason
  });
}

function onRunContinued(event) {
  const wasRunning = state.running;
  state.running = true;
  state.runId = event.runId || state.runId;
  markSessionStatus('RUNNING');
  syncComposer();
  if (!wasRunning) {
    startRunClock(Date.now());
    showThinking();
  }
  if (event.runId && !state.turnRuns.includes(event.runId)) {
    state.turnRuns.push(event.runId);
  }
  const attempt = Number(event.payload.attempt || 1);
  const max = Number(event.payload.maxAttempts || 0);
  showNotice(`自动继续 ${attempt}/${max}：${event.payload.reason || ''}`);
  setRunState('running', '自动继续中');
  record(event, {
    kind: 'run',
    tone: 'ok',
    status: max ? `${attempt}/${max}` : '继续',
    title: '计划未完成，自动继续',
    detail: event.payload.reason
  });
}

function onSubagentStarted(event) {
  appendSubagent(event.payload);
}

function onSubagentFinished(event) {
  finishSubagent(event.payload);
}

function onTaskUpdated(event) {
  renderTaskBoard(event.payload);
  record(event, {
    kind: 'task',
    tone: taskTone(event.payload.status),
    status: TASK_STATUS_LABEL[event.payload.status] || event.payload.status,
    title: [event.payload.taskId, event.payload.title].filter(Boolean).join(' '),
    detail: [event.payload.agentId ? '@' + event.payload.agentId : '', event.payload.detail || '']
      .filter(Boolean).join(' · ')
  });
}

function onSandboxDiff(event) {
  renderSandboxChange(event.payload);
  record(event, {
    kind: 'sandbox',
    tone: 'warn',
    status: '沙箱',
    title: event.payload.path,
    detail: `@${event.payload.agentId || '?'} · +${event.payload.added || 0} -${event.payload.removed || 0}`
  });
}

function onArbitrationRequired(event) {
  renderArbitration(event.payload);
  // 只是提示状态，运行态仍由 run.started / run.completed 维护，避免提前把界面放回空闲
  setRunState('warn', '待你裁决');
  record(event, {
    kind: 'arbitration',
    tone: 'error',
    status: '待仲裁',
    title: event.payload.kind === 'conflict' ? '变更冲突' : event.payload.kind === 'verification'
      ? '硬校验未通过' : '方案分歧',
    detail: '需要你决定后才会继续'
  });
}

function onArbitrationResolved(event) {
  settleArbitration(event.payload);
  record(event, {
    kind: 'arbitration',
    tone: 'ok',
    status: '已裁决',
    title: event.payload.choice || '',
    detail: event.payload.arbitrationId
  });
}

/* ---------- 对话渲染 ---------- */

/** 上下文压缩：在对话流里留一条可展开的交接摘要，让「记忆断层」有迹可查。 */
function appendCompaction(payload) {
  const card = document.createElement('details');
  card.className = 'ctx-card';

  const head = document.createElement('summary');
  head.className = 'ctx-head';
  head.textContent = `上下文已压缩 · 折叠 ${payload.coveredMessages || 0} 条较早消息 · `
    + `${fmtChars(payload.charactersBefore)} → ${fmtChars(payload.charactersAfter)}`;

  const body = document.createElement('pre');
  body.className = 'ctx-body';
  body.textContent = payload.summary || '（摘要为空）';

  card.append(head, body);
  el.messages.append(card);
  hideEmptyState();
}

/**
 * 每轮结束在对话流里留一条结论：干完了没有、还剩什么、改了哪些文件。
 * 没有这一条时，用户只能凭最后一条工具卡片猜本轮是结束了还是卡住了。
 */
function appendTurnResult({ tone, title, detail, openSteps, changedFiles, runIds, hint, actionLabel, onAction, resumable }) {
  const card = document.createElement('article');
  card.className = 'turn-card is-' + (tone || 'ok');

  const head = document.createElement('div');
  head.className = 'turn-head';
  const mark = document.createElement('span');
  mark.className = 'turn-mark';
  mark.textContent = tone === 'ok' ? '✓' : tone === 'warn' ? '!' : '×';
  const label = document.createElement('strong');
  label.textContent = title || '本轮结束';
  head.append(mark, label);
  card.append(head);

  if (detail) {
    const note = document.createElement('p');
    note.className = 'turn-note';
    note.textContent = detail;
    card.append(note);
  }

  if (openSteps && openSteps.length) {
    const list = document.createElement('ul');
    list.className = 'turn-open';
    for (const step of openSteps) {
      const item = document.createElement('li');
      item.textContent = step;
      list.append(item);
    }
    card.append(list);
  }

  if (hint) {
    const tip = document.createElement('p');
    tip.className = 'turn-hint';
    tip.textContent = hint;
    card.append(tip);
  }

  if (changedFiles && changedFiles.length) {
    card.append(runChangeDetails(runIds, changedFiles));
  }

  const actions = [];
  if (resumable) {
    actions.push(button('继续执行', 'btn btn-primary', () => continueRun(card)));
  }
  if (actionLabel && typeof onAction === 'function') {
    actions.push(button(actionLabel, 'btn btn-sm', onAction));
  }
  if (actions.length) {
    const row = document.createElement('div');
    row.className = 'turn-actions';
    row.append(...actions);
    card.append(row);
  }

  const parent = state.turnShell && state.turnShell.shell.isConnected
    ? state.turnShell.shell
    : el.messages;
  parent.append(card);
  hideEmptyState();
  scrollThread();
}

/**
 * Codex 风格改动卡：默认收起，摘要只报「改了 N 个文件 +x −y」，
 * 展开后按文件列状态与增删行数，再点单个文件才拉取并渲染 diff。
 */
function runChangeDetails(runIds, changedFiles) {
  const paths = changedFiles.map(normalizePath);
  const details = document.createElement('details');
  details.className = 'turn-changes';

  const head = document.createElement('summary');
  head.className = 'turn-changes-head';
  const label = document.createElement('span');
  label.textContent = `改动 ${paths.length} 个文件`;
  const stat = document.createElement('span');
  stat.className = 'turn-changes-stat';
  head.append(label, stat);

  const body = document.createElement('div');
  body.className = 'turn-changes-body';
  details.append(head, body);

  let started = false;
  const start = () => {
    if (started) {
      return;
    }
    started = true;
    loadRunChangeDetail(runIds, paths, stat, body);
  };
  details.addEventListener('toggle', () => {
    if (details.open) {
      start();
    }
  });
  // 刚跑完的这一轮立刻取一次统计，摘要行才有 +x −y；回放历史时按需加载，避免一次打几十个请求
  if (!state.replaying) {
    start();
  }
  return details;
}

async function loadRunChangeDetail(runIds, paths, stat, body) {
  const ids = (runIds || []).filter(Boolean);
  body.replaceChildren(changeHint('读取改动中…'));
  if (!ids.length) {
    stat.textContent = '';
    body.replaceChildren(runChangeFallback(paths));
    return;
  }
  try {
    const reports = await Promise.all(ids.map(async (runId) => {
      const response = await apiFetch(`/api/runs/${encodeURIComponent(runId)}/changes`);
      if (!response.ok) {
        throw new Error('HTTP ' + response.status);
      }
      return response.json();
    }));
    renderRunChangeDetail(mergeRunReports(reports), paths, stat, body);
  } catch (error) {
    stat.textContent = '';
    body.replaceChildren(
      changeHint('读取改动失败：' + error.message),
      runChangeFallback(paths)
    );
  }
}

/**
 * 合并一轮里多个 run 的改动：同一文件以最后一次运行的统计为准
 * （最后一次的 diff 本身就是相对 HEAD 的完整改动，直接相加会重复计数）。
 */
function renderRunChangeDetail(report, paths, stat, body) {
  const files = Array.isArray(report.files) ? report.files : [];
  const added = Number(report.added || 0);
  const removed = Number(report.removed || 0);

  stat.replaceChildren();
  if (added || removed) {
    const add = document.createElement('span');
    add.className = 'add';
    add.textContent = '+' + added;
    const del = document.createElement('span');
    del.className = 'del';
    del.textContent = '−' + removed;
    stat.append(add, document.createTextNode(' '), del);
  }

  body.replaceChildren();
  if (!files.length) {
    body.append(runChangeFallback(paths));
    return;
  }
  if (!report.gitRepository) {
    body.append(changeHint('工作区不是 git 仓库：只能列出本轮写过的文件，没有 diff。'));
  }
  for (const file of files) {
    body.append(runChangeRow(file, report.gitRepository));
  }
  if (report.truncated) {
    body.append(changeHint('改动过多，diff 已截断。'));
  }
  // 本轮清单只覆盖 Agent 的写文件工具；命令行改出来的文件要到「改动」面板看
  const actions = document.createElement('div');
  actions.className = 'turn-changes-actions';
  actions.append(button('查看全部改动', 'btn btn-sm', () => selectTab('changes')));
  body.append(actions);
}

/** 后端没有快照（老运行或快照已清理）时的兜底：只列文件名。 */
function runChangeFallback(paths) {
  const node = document.createElement('div');
  node.className = 'run-change-flat';
  node.textContent = paths.length ? paths.join('、') : '本次运行没有改动任何文件。';
  return node;
}

function runChangeRow(file, gitRepository) {
  const row = document.createElement('details');
  row.className = 'diff-file run-change-row';

  const summary = document.createElement('summary');
  const status = document.createElement('span');
  status.className = 'change-status is-' + statusKind(file.status || 'M');
  status.textContent = file.status || 'M';
  const path = document.createElement('span');
  path.className = 'diff-path';
  path.textContent = file.path;
  path.title = file.path;
  summary.append(status, path);

  if (file.added || file.removed) {
    const stat = document.createElement('span');
    stat.className = 'diff-stat';
    const add = document.createElement('span');
    add.className = 'add';
    add.textContent = '+' + Number(file.added || 0);
    const del = document.createElement('span');
    del.className = 'del';
    del.textContent = ' −' + Number(file.removed || 0);
    stat.append(add, del);
    summary.append(stat);
  }
  row.append(summary);

  if (!file.diff) {
    path.title = file.path + (gitRepository ? '（无 diff）' : '（非 git 仓库）');
    return row;
  }

  row.addEventListener('toggle', () => {
    if (!row.open || row.dataset.rendered) {
      return;
    }
    row.dataset.rendered = '1';
    const body = document.createElement('div');
    body.className = 'diff-lines';
    const parsed = parseDiff(file.diff);
    if (!parsed.length) {
      body.append(changeHint('diff 为空。'));
    } else {
      for (const line of parsed[0].lines) {
        body.append(diffLineRow(line));
      }
    }
    row.append(body);
  });
  return row;
}

function fmtChars(value) {
  const n = Number(value || 0);
  return n >= 1000 ? `${(n / 1000).toFixed(1)}k 字符` : `${n} 字符`;
}

/** 子代理：一条折叠卡片，展开可看它回传的结论。 */
function appendSubagent(payload) {
  const card = document.createElement('details');
  card.className = 'subagent-card';
  card.dataset.agentId = payload.agentId || '';

  const head = document.createElement('summary');
  head.className = 'subagent-head';
  head.textContent = `子任务：${payload.task || ''}`;

  const body = document.createElement('pre');
  body.className = 'subagent-body';
  body.textContent = '执行中…';

  card.append(head, body);
  el.messages.append(card);
  hideEmptyState();
}

function finishSubagent(payload) {
  const id = payload.agentId || '';
  const card = el.messages.querySelector(`.subagent-card[data-agent-id="${CSS.escape(id)}"]`);
  if (!card) {
    return;
  }
  card.classList.toggle('is-failed', payload.ok === false);
  const body = card.querySelector('.subagent-body');
  if (body) {
    body.textContent = payload.summary || (payload.ok === false ? '子代理失败' : '完成');
  }
}

/** 多智能体任务看板：同一 taskId 就地更新状态，不重复插卡片。 */
const TASK_STATUS_LABEL = {
  pending: '⏳等待',
  running: '🔄运行中',
  done: '✅完成',
  dissent: '⚠️待仲裁',
  conflict: '🔴冲突',
  failed: '❌失败',
  blocked: '⛔已阻断',
  cancelled: '🚫已取消'
};

function taskTone(status) {
  switch (status) {
    case 'done': return 'ok';
    case 'running': return 'run';
    case 'failed':
    case 'conflict': return 'error';
    case 'dissent':
    case 'blocked':
    case 'cancelled': return 'warn';
    default: return 'text';
  }
}

function insertIntoThread(node) {
  const shell = state.turnShell && state.turnShell.shell.isConnected ? state.turnShell.shell : null;
  if (shell) {
    shell.before(node);
  } else {
    el.messages.append(node);
  }
  hideEmptyState();
}

function renderTaskBoard(payload) {
  const taskId = String(payload.taskId || '');
  if (!taskId) {
    return;
  }
  let card = el.messages.querySelector('.multi-card');
  if (!card) {
    card = document.createElement('aside');
    card.className = 'multi-card';

    const head = document.createElement('div');
    head.className = 'multi-head';
    const title = document.createElement('div');
    title.className = 'multi-title';
    title.textContent = '多智能体任务';
    const count = document.createElement('span');
    count.className = 'multi-count';
    head.append(title, count);

    const list = document.createElement('ul');
    list.className = 'multi-list';
    card.append(head, list);
    insertIntoThread(card);
  }

  const list = card.querySelector('.multi-list');
  let row = list.querySelector(`.multi-item[data-task-id="${CSS.escape(taskId)}"]`);
  if (!row) {
    row = document.createElement('li');
    row.className = 'multi-item';
    row.dataset.taskId = taskId;

    const name = document.createElement('span');
    name.className = 'multi-name';
    const status = document.createElement('span');
    status.className = 'multi-status';
    const agent = document.createElement('span');
    agent.className = 'multi-agent';
    const detail = document.createElement('span');
    detail.className = 'multi-detail';
    row.append(name, status, agent, detail);
    list.append(row);
  }

  const status = String(payload.status || 'pending');
  row.dataset.status = status;
  row.classList.toggle('is-done', status === 'done');
  row.classList.toggle('is-failed', status === 'failed' || status === 'conflict');
  row.classList.toggle('is-warn', status === 'dissent' || status === 'blocked' || status === 'cancelled');
  row.querySelector('.multi-name').textContent = `${taskId} ${payload.title || ''}`.trim();
  row.querySelector('.multi-status').textContent = TASK_STATUS_LABEL[status] || status;
  row.querySelector('.multi-agent').textContent = payload.agentId ? `@${payload.agentId}` : '';
  row.querySelector('.multi-detail').textContent = payload.detail || '';

  const rows = Array.from(list.children);
  const finished = rows.filter((item) => item.dataset.status === 'done').length;
  card.querySelector('.multi-count').textContent = `${finished}/${rows.length}`;
}

/** 沙箱改动：标清「哪个子 Agent 改了哪个文件」，与真实 git 改动分开。 */
function renderSandboxChange(payload) {
  const path = String(payload.path || '');
  if (!path) {
    return;
  }
  let card = el.messages.querySelector('.sandbox-card');
  if (!card) {
    card = document.createElement('aside');
    card.className = 'sandbox-card';
    const head = document.createElement('div');
    head.className = 'sandbox-head';
    head.textContent = '沙箱改动（待你接受）';
    const list = document.createElement('ul');
    list.className = 'sandbox-list';
    card.append(head, list);
    insertIntoThread(card);
  }
  const key = `${payload.sandboxId || ''}|${path}`;
  const list = card.querySelector('.sandbox-list');
  let row = list.querySelector(`.sandbox-item[data-key="${CSS.escape(key)}"]`);
  if (!row) {
    row = document.createElement('li');
    row.className = 'sandbox-item';
    row.dataset.key = key;
    row.append(document.createElement('span'), document.createElement('span'), document.createElement('span'));
    list.append(row);
  }
  const [file, delta, owner] = row.children;
  file.className = 'sandbox-file';
  file.textContent = path;
  delta.className = 'sandbox-delta';
  delta.textContent = `+${payload.added || 0} -${payload.removed || 0}`;
  owner.className = 'sandbox-owner';
  owner.textContent = `@${payload.agentId || '?'}`;

  renderSandboxActions(card, String(payload.sandboxId || ''), payload.taskId);
}

/** 每个沙箱单独接受 / 拒绝：支持单独回滚，不整体放行。 */
function renderSandboxActions(card, sandboxId, taskId) {
  if (!sandboxId) {
    return;
  }
  let foot = card.querySelector('.sandbox-actions');
  if (!foot) {
    foot = document.createElement('div');
    foot.className = 'sandbox-actions';
    card.append(foot);
  }
  if (foot.querySelector(`.sandbox-group[data-sandbox="${CSS.escape(sandboxId)}"]`)) {
    return;
  }
  const group = document.createElement('div');
  group.className = 'sandbox-group';
  group.dataset.sandbox = sandboxId;

  const label = document.createElement('span');
  label.className = 'sandbox-label';
  label.textContent = `${taskId || ''} ${sandboxId}`.trim();

  const accept = document.createElement('button');
  accept.type = 'button';
  accept.className = 'sandbox-btn is-accept';
  accept.textContent = '接受变更';
  accept.addEventListener('click', () => decideSandbox(group, sandboxId, 'accept'));

  const reject = document.createElement('button');
  reject.type = 'button';
  reject.className = 'sandbox-btn';
  reject.textContent = '拒绝';
  reject.addEventListener('click', () => decideSandbox(group, sandboxId, 'reject'));

  group.append(label, accept, reject);
  foot.append(group);
}

async function decideSandbox(group, sandboxId, action) {
  group.querySelectorAll('button').forEach((button) => {
    button.disabled = true;
  });
  try {
    const response = await apiFetch(`/api/sessions/${encodeURIComponent(state.sessionId)}/multi/sandboxes/${encodeURIComponent(sandboxId)}/${action}`, {
      method: 'POST'
    });
    if (action === 'reject') {
      group.classList.add('is-rejected');
      showNotice(`已拒绝 ${sandboxId} 的改动`);
      return;
    }
    const data = await response.json().catch(() => ({}));
    if (data.ok) {
      group.classList.add('is-accepted');
      showNotice(`已接受 ${sandboxId}：写入 ${(data.written || []).length} 个文件`);
    } else {
      group.classList.add('is-rejected');
      showNotice(`未能写入：${(data.failed && data.failed.join('；')) || data.message || '未知错误'}`);
    }
  } catch (error) {
    showNotice(`操作失败：${error.message}`);
  }
}

/** 仲裁卡：各方方案 + 证据 + 风险都在报告里，按钮只做「人来决定」。 */
function renderArbitration(payload) {
  const id = String(payload.arbitrationId || '');
  if (!id || el.messages.querySelector(`.arb-card[data-arb-id="${CSS.escape(id)}"]`)) {
    return;
  }
  const card = document.createElement('aside');
  card.className = 'arb-card';
  card.dataset.arbId = id;

  const head = document.createElement('div');
  head.className = 'arb-head';
  head.textContent = payload.kind === 'conflict'
    ? '🔴 变更冲突，等你裁决'
    : payload.kind === 'verification' ? '❌ 硬校验未通过，等你裁决' : '⚠️ 方案分歧，等你裁决';

  const body = document.createElement('pre');
  body.className = 'arb-body';
  body.textContent = payload.report || '';

  const actions = document.createElement('div');
  actions.className = 'arb-actions';
  const options = Array.isArray(payload.options) ? payload.options : [];
  options.forEach((option) => {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'arb-option';
    button.textContent = option;
    button.addEventListener('click', () => resolveArbitration(id, option));
    actions.append(button);
  });

  const note = document.createElement('p');
  note.className = 'arb-choice';
  note.textContent = '你不选，任务及其下游不会继续。';

  card.append(head, body, actions, note);
  insertIntoThread(card);
}

async function resolveArbitration(arbitrationId, choice) {
  const card = el.messages.querySelector(`.arb-card[data-arb-id="${CSS.escape(arbitrationId)}"]`);
  if (card) {
    card.classList.add('is-resolved');
    card.querySelectorAll('.arb-option').forEach((button) => {
      button.disabled = true;
    });
    const note = card.querySelector('.arb-choice');
    if (note) {
      note.textContent = `已选择：${choice}`;
    }
  }
  try {
    await apiFetch(`/api/sessions/${encodeURIComponent(state.sessionId)}/multi/arbitrations/${encodeURIComponent(arbitrationId)}/resolve`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ choice })
    });
  } catch (error) {
    console.warn('[arbitration] 记录失败', error);
  }
  // 把决定交回对话，父 Agent 下一轮据此继续；不要在界面里替它推进
  await submitMessage(`【仲裁结果】${choice}`);
}

function settleArbitration(payload) {
  const id = String(payload.arbitrationId || '');
  const card = id ? el.messages.querySelector(`.arb-card[data-arb-id="${CSS.escape(id)}"]`) : null;
  if (!card) {
    return;
  }
  card.classList.add('is-resolved');
  card.querySelectorAll('.arb-option').forEach((button) => {
    button.disabled = true;
  });
  const note = card.querySelector('.arb-choice');
  if (note && payload.choice) {
    note.textContent = `已选择：${payload.choice}`;
  }
}

/** 任务计划：整表重绘，只保留一个卡片并跟在最新消息后面。 */
const PLAN_STATUS_LABEL = { pending: '等待', in_progress: '进行中', completed: '完成' };

function renderPlan(payload) {
  const items = Array.isArray(payload && payload.plan) ? payload.plan : [];
  let card = el.messages.querySelector('.plan-card');
  if (!card) {
    card = document.createElement('aside');
    card.className = 'plan-card';

    const head = document.createElement('div');
    head.className = 'plan-head';
    const title = document.createElement('div');
    title.className = 'plan-title';
    title.textContent = '任务计划';
    const count = document.createElement('span');
    count.className = 'plan-count';
    head.append(title, count);

    const bar = document.createElement('div');
    bar.className = 'plan-bar';
    bar.append(document.createElement('i'));

    const list = document.createElement('ol');
    list.className = 'plan-list';
    card.append(head, bar, list);
  }
  if (!items.length) {
    card.remove();
    return;
  }

  // 进度条按完成数走，和标题右边的 4/6 对齐，一眼知道还剩多少
  const done = items.filter((item) => item.status === 'completed').length;
  const total = items.length;
  card.querySelector('.plan-count').textContent = `${done}/${total}`;
  card.querySelector('.plan-bar i').style.width = `${total ? Math.round((done / total) * 100) : 0}%`;

  const list = card.querySelector('.plan-list');
  list.replaceChildren();
  items.forEach((item, index) => {
    const status = item.status || 'pending';
    const li = document.createElement('li');
    li.className = `plan-item is-${status}`;
    const num = document.createElement('span');
    num.className = 'plan-index';
    num.textContent = String(index + 1).padStart(2, '0');
    const text = document.createElement('span');
    text.className = 'plan-text';
    text.textContent = item.step || '';
    const pill = document.createElement('span');
    pill.className = 'plan-status';
    pill.textContent = PLAN_STATUS_LABEL[status] || '等待';
    li.append(num, text, pill);
    list.append(li);
  });
  // 计划跟在这一轮前面。操作和结论在外壳里，结论仍是这一轮的最后一段
  const shell = state.turnShell && state.turnShell.shell.isConnected ? state.turnShell.shell : null;
  if (shell) {
    shell.before(card);
  } else {
    el.messages.append(card);
  }
  hideEmptyState();
}

/** 刷新后从服务端恢复计划清单（事件已重放的那份会覆盖这一份）。 */
async function loadPlan() {
  if (!state.sessionId) {
    return;
  }
  try {
    const response = await apiFetch(`/api/sessions/${encodeURIComponent(state.sessionId)}/plan`);
    if (!response.ok) {
      return;
    }
    const data = await response.json();
    if (Array.isArray(data.plan) && data.plan.length) {
      renderPlan(data);
    }
  } catch (error) {
    // 计划恢复失败不影响对话
  }
}

function showThinking() {
  clearThinking();
  const wrapper = document.createElement('article');
  wrapper.className = 'msg msg-assistant';

  const who = document.createElement('div');
  who.className = 'who';
  const mark = document.createElement('span');
  mark.className = 'who-mark';
  mark.textContent = 'P';
  who.append(mark, document.createTextNode('Pilot · 正在处理'));

  const bubble = document.createElement('div');
  bubble.className = 'bubble typing';
  for (let i = 0; i < 3; i++) {
    bubble.append(document.createElement('span'));
  }

  wrapper.append(who, bubble);
  // 跟在这一轮的操作和结论后面，不要插到结论上面
  const shell = state.turnShell;
  if (shell && shell.shell.isConnected) {
    shell.shell.append(wrapper);
  } else {
    el.messages.append(wrapper);
  }
  state.thinking = wrapper;
  if (!state.replaying) {
    scrollThread();
  }
}

/** 下一轮开始前丢掉当前外壳的引用，已经画出来的那一组留在原位。 */
function beginTurnShell() {
  state.turnShell = null;
}

/**
 * 一轮对话的容器：操作默认收成一行，结论文本始终排在操作后面。
 * 模型往往先吐一句再调工具，如果结论气泡先挂到对话末尾，后到的操作就会堆在结论下面。
 */
function ensureTurnShell() {
  if (state.turnShell && state.turnShell.shell.isConnected) {
    return state.turnShell;
  }
  const shell = document.createElement('div');
  shell.className = 'turn-shell';

  const tools = document.createElement('details');
  tools.className = 'tool-group';
  tools.hidden = true;

  const summary = document.createElement('summary');
  summary.className = 'tool-group-head';

  const chevron = document.createElement('span');
  chevron.className = 'tool-group-chevron';
  chevron.setAttribute('aria-hidden', 'true');

  const label = document.createElement('span');
  label.className = 'tool-group-label';
  label.textContent = '操作';

  const count = document.createElement('span');
  count.className = 'tool-group-count';

  summary.append(chevron, label, count);

  const list = document.createElement('div');
  list.className = 'tool-group-list';
  tools.append(summary, list);

  const replySlot = document.createElement('div');
  replySlot.className = 'turn-reply';

  shell.append(tools, replySlot);
  el.messages.append(shell);

  const turn = {
    shell,
    tools,
    list,
    label,
    count,
    replySlot,
    total: 0,
    running: 0,
    counts: new Map()
  };
  state.turnShell = turn;
  return turn;
}

const TOOL_LABELS = {
  read_file: '读取',
  list_dir: '列目录',
  search_text: '搜索',
  edit_file: '编辑',
  create_file: '新建',
  run_command: '命令',
  update_plan: '计划'
};

function toolLabel(name) {
  return TOOL_LABELS[name] || name || '操作';
}

function refreshToolGroup(turn) {
  if (!turn || turn.total <= 0) {
    if (turn) {
      turn.tools.hidden = true;
    }
    return;
  }
  turn.tools.hidden = false;
  const bits = [];
  for (const [name, n] of turn.counts) {
    bits.push(`${toolLabel(name)} ${n}`);
  }
  turn.label.textContent = turn.running > 0 ? `正在执行 ${turn.total} 项` : `${turn.total} 项操作`;
  turn.count.textContent = bits.join(' · ');
}

function noteToolStarted(turn, name) {
  turn.total += 1;
  turn.running += 1;
  turn.counts.set(name, (turn.counts.get(name) || 0) + 1);
  refreshToolGroup(turn);
}

function noteToolSettled(turn) {
  if (!turn) {
    return;
  }
  turn.running = Math.max(0, turn.running - 1);
  refreshToolGroup(turn);
}

function clearThinking() {
  if (state.thinking) {
    state.thinking.remove();
    state.thinking = null;
  }
}

function startReply() {
  const wrapper = document.createElement('article');
  wrapper.className = 'msg msg-assistant';

  const who = document.createElement('div');
  who.className = 'who';
  const mark = document.createElement('span');
  mark.className = 'who-mark';
  mark.textContent = 'P';
  who.append(mark, document.createTextNode('Pilot'));

  const bubble = document.createElement('div');
  bubble.className = 'bubble';

  wrapper.append(who, bubble);
  ensureTurnShell().replySlot.append(wrapper);
  state.reply = bubble;
  state.replyText = '';
}

/** 模型还在吐字。失败重试时整段丢掉，成功后由落库的全文覆盖这一段。 */
function showLivePreview(text) {
  if (!text) {
    return;
  }
  if (!state.replyLive) {
    state.replyCommitted = state.reply ? (state.replyText || '') : '';
    state.replyLive = true;
  }
  appendDelta(text);
}

/** 这一轮的完整回复。预览已经拼过的话，用全文盖上，避免再接一遍。 */
function commitReply(text) {
  const piece = text || '';
  if (state.replyLive) {
    const full = (state.replyCommitted || '') + piece;
    if (!state.reply) {
      startReply();
    }
    state.replyText = full;
    state.reply.dataset.text = full;
    state.replyLive = false;
    state.replyCommitted = full;
    if (!state.replaying) {
      scheduleReplyRender();
    }
    return;
  }
  appendDelta(piece);
  state.replyCommitted = state.replyText;
}

/** 这次请求失败了，把还没落库的预览从气泡上撤掉。 */
function rollbackLivePreview() {
  if (!state.replyLive) {
    return;
  }
  const base = state.replyCommitted || '';
  state.replyLive = false;
  state.replyText = base;
  if (!state.reply) {
    return;
  }
  state.reply.dataset.text = base;
  if (!base) {
    const article = state.reply.closest('article');
    if (article) {
      article.remove();
    }
    state.reply = null;
    return;
  }
  if (!state.replaying) {
    scheduleReplyRender();
  }
}

function appendDelta(text) {
  if (!text) {
    return;
  }
  clearThinking();
  if (!state.reply) {
    startReply();
  }
  state.replyText += text;
  // 文本同时挂在气泡自身上：run.completed 可能在同一帧到达并把 state.reply 置空，
  // 若只依赖 state.replyText，最后一帧的 rAF 会因为 state.reply === null 直接放弃渲染，
  // 于是界面上只剩一个空的「Pilot」气泡。
  state.reply.dataset.text = state.replyText;
  if (!state.replaying) {
    scheduleReplyRender();
  }
}

/** 流式时每帧最多重绘一次，避免长回答的 O(n²) 重排。 */
function scheduleReplyRender() {
  if (state.renderQueued) {
    return;
  }
  state.renderQueued = true;
  const bubble = state.reply;
  requestAnimationFrame(() => {
    state.renderQueued = false;
    if (!bubble) {
      return;
    }
    const text = bubble.dataset.text || '';
    if (!text) {
      return;
    }
    const stick = isNearBottom(el.messages);
    bubble.replaceChildren(renderMarkdown(text));
    if (stick) {
      scrollThread();
    }
  });
}

function finishReply(failed) {
  clearThinking();
  const bubble = state.reply;
  if (!bubble) {
    return;
  }
  // 收尾时同步渲染一次：即使最后一帧的 requestAnimationFrame 还没执行也不会丢内容
  const text = state.replyText || bubble.dataset.text || '';
  if (text) {
    bubble.replaceChildren(renderMarkdown(text));
  } else {
    bubble.classList.add('is-muted');
    bubble.textContent = failed ? '（本轮没有返回文本）' : '（本轮只调用了工具，没有文本输出）';
  }
  state.reply = null;
  state.replyText = '';
  state.replyLive = false;
  state.replyCommitted = '';
}

function appendUserMessage(text, sequence, { allowTaskActions = true } = {}) {
  const wrapper = document.createElement('article');
  wrapper.className = 'msg msg-user';
  wrapper.dataset.text = text;
  if (sequence) {
    wrapper.dataset.sequence = String(sequence);
  }

  const bubble = document.createElement('div');
  bubble.className = 'bubble';
  bubble.textContent = text;

  wrapper.append(bubble);
  // 纠正是当前运行内的输入，不是独立任务起点，后端没有对应的编辑或检查点恢复入口。
  if (allowTaskActions) {
    const actions = document.createElement('div');
    actions.className = 'msg-actions';
    actions.append(button('编辑', 'msg-edit-btn', () => beginEditUserMessage(wrapper)));
    actions.append(button('回退 / 分支', 'msg-edit-btn', () => openMessageCheckpoint(wrapper)));
    wrapper.append(actions);
  }
  el.messages.append(wrapper);
  hideEmptyState();
}

/** 乐观渲染时还没有序号，run.started 到达后补上，编辑才知道改的是哪一句。 */
function stampLastUserMessage(sequence, text) {
  const nodes = el.messages.querySelectorAll('.msg-user');
  const last = nodes[nodes.length - 1];
  if (!last || last.dataset.text !== text || !sequence) {
    return;
  }
  last.dataset.sequence = String(sequence);
}

/** 选择的是用户消息发送前的检查点；代码和模型记忆分别恢复。 */
async function openMessageCheckpoint(wrapper) {
  if (state.running) {
    showNotice('请等任务结束或停止后，再选择消息回退。');
    return;
  }
  const sequence = Number(wrapper.dataset.sequence);
  const sessionId = state.sessionId;
  if (!sequence || !sessionId) {
    showNotice('这条消息没有独立检查点，请选择它所属的任务。');
    return;
  }
  const dialog = document.createElement('dialog');
  dialog.className = 'checkpoint-dialog';
  dialog.setAttribute('aria-labelledby', 'checkpoint-title');
  const title = document.createElement('h2');
  title.id = 'checkpoint-title';
  title.textContent = '回到这条消息发送前';
  const excerpt = document.createElement('blockquote');
  excerpt.textContent = wrapper.dataset.text || '';
  const info = document.createElement('p');
  info.className = 'checkpoint-hint';
  info.textContent = '正在检查后续改动…';
  const details = document.createElement('div');
  details.className = 'checkpoint-details';
  details.setAttribute('role', 'status');
  const choices = document.createElement('fieldset');
  choices.className = 'checkpoint-choices';
  const legend = document.createElement('legend');
  legend.textContent = '选择回退范围';
  choices.append(legend);
  const options = new Map();
  for (const [value, label, description] of [
    ['both', '一起撤', '还原文件，移除这条消息及后续对话，并回填原消息。'],
    ['code', '只撤代码', '还原后续任务改过的文件，保留当前对话。'],
    ['conversation', '只撤对话', '保留当前文件，移除这条消息及后续对话，并回填原消息。']
  ]) {
    const row = document.createElement('label');
    const input = document.createElement('input');
    input.type = 'radio';
    input.name = 'checkpoint-mode';
    input.value = value;
    const text = document.createElement('span');
    const name = document.createElement('strong');
    name.textContent = label;
    const hint = document.createElement('small');
    hint.textContent = description;
    text.append(name, hint);
    row.append(input, text);
    choices.append(row);
    options.set(value, input);
  }
  const branchHint = document.createElement('p');
  branchHint.className = 'checkpoint-hint';
  branchHint.textContent = '另开对话分支：保留原聊天，从此处的历史开始；新聊天使用当前工作区文件。Git 分支可在变更面板单独创建。';
  const footer = document.createElement('div');
  footer.className = 'checkpoint-actions';
  const cancel = button('取消', 'btn btn-sm', () => dialog.close());
  const fork = button('另开对话分支', 'btn btn-sm', () => applyCheckpoint('fork'));
  const restore = button('确认回退', 'btn btn-primary btn-sm', () => applyCheckpoint('restore'));
  fork.disabled = true;
  restore.disabled = true;
  footer.append(cancel, fork, restore);
  dialog.append(title, excerpt, info, details, choices, branchHint, footer);
  let busy = false;
  let previewData = null;
  dialog.addEventListener('cancel', (event) => { if (busy) event.preventDefault(); });
  dialog.addEventListener('close', () => dialog.remove());
  dialog.addEventListener('keydown', (event) => event.stopPropagation());
  document.body.append(dialog);
  dialog.showModal();
  cancel.focus();
  const url = `/api/sessions/${encodeURIComponent(sessionId)}/checkpoints`;

  function updateChoices() {
    if (!previewData) return;
    const canCode = previewData.code.ready && previewData.code.files.length > 0;
    options.get('both').disabled = !canCode || !previewData.conversationAvailable;
    options.get('code').disabled = !canCode;
    options.get('conversation').disabled = !previewData.conversationAvailable;
    const selected = [...options.values()].find((input) => input.checked && !input.disabled)
      || [...options.values()].find((input) => !input.disabled);
    for (const input of options.values()) input.checked = input === selected;
    restore.disabled = !selected;
    fork.disabled = !previewData.conversationAvailable;
  }

  function renderPreview(data) {
    previewData = data;
    info.textContent = `将回退所选消息及之后的 ${data.runs} 轮任务，涉及 ${data.code.files.length} 个文件。新建文件会被删除。`;
    details.replaceChildren();
    for (const [label, paths] of [
      ['检测到后续修改，代码回退已阻止', data.code.conflicts],
      ['快照不可用，无法回退代码', data.code.unavailable],
      ['涉及文件', data.code.files]
    ]) {
      if (!paths.length) continue;
      const heading = document.createElement('p');
      heading.textContent = label;
      const list = document.createElement('ul');
      for (const path of paths) {
        const item = document.createElement('li');
        item.textContent = path;
        list.append(item);
      }
      details.append(heading, list);
    }
    if (!data.conversationAvailable) {
      const hint = document.createElement('p');
      hint.textContent = '这条历史消息没有可用的对话检查点，无法回退对话或另开对话分支。';
      details.append(hint);
    }
    updateChoices();
  }

  async function refreshPreview() {
    const response = await apiFetch(`${url}?sequence=${sequence}`);
    if (!response.ok) throw new Error(await describeError(response));
    const data = await response.json();
    if (dialog.isConnected) renderPreview(data);
  }

  async function applyCheckpoint(action) {
    const mode = [...options.values()].find((input) => input.checked)?.value;
    if (busy || !previewData || (action === 'restore' && !mode)) return;
    busy = true;
    for (const node of dialog.querySelectorAll('button, input')) node.disabled = true;
    try {
      const response = await apiFetch(`${url}/${action}`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sequence, mode, revision: previewData.revision })
      });
      if (!response.ok) throw new Error(await describeError(response));
      const data = await response.json();
      if (action === 'restore' && !data.applied) {
        await refreshPreview();
        throw new Error('文件校验未通过，回退未完成；请检查冲突或缺失快照。');
      }
      dialog.close();
      closeStream();
      await loadSessions();
      if (action === 'fork') {
        await activate(data.sessionId, data.title, data.workspace);
      } else {
        await activate(sessionId, state.sessionTitle, state.workspace);
      }
      if (action === 'fork' || mode !== 'code') {
        const draft = el.input.value.trim();
        el.input.value = draft && draft !== data.input ? `${draft}\n\n${data.input}` : data.input;
        autoGrow();
        el.input.focus();
      }
      loadChanges();
      showNotice(action === 'fork' ? '已创建对话分支，原聊天已保留；当前工作区文件继续共用。'
        : mode === 'code' ? '代码已回退，对话已保留。' : mode === 'conversation' ? '对话已回退，原消息已回填。' : '代码和对话已回退，原消息已回填。');
    } catch (error) {
      info.textContent = '操作失败：' + error.message;
    } finally {
      busy = false;
      cancel.disabled = false;
      updateChoices();
    }
  }

  try { await refreshPreview(); }
  catch (error) { info.textContent = '检查点读取失败：' + error.message; }
}

function beginEditUserMessage(wrapper) {
  if (state.running) {
    showNotice('这句已经发出去了。运行中要改的话，改输入框上方队列里还没轮到的那句。');
    return;
  }
  const sequence = Number(wrapper.dataset.sequence);
  if (!sequence) {
    showNotice('这句是中途追加的，请改它所属的那条任务。');
    return;
  }
  if (wrapper.querySelector('.msg-editor')) {
    return;
  }
  const original = wrapper.dataset.text || '';
  const editor = document.createElement('div');
  editor.className = 'msg-editor';
  const field = document.createElement('textarea');
  field.value = original;
  const actions = document.createElement('div');
  actions.className = 'msg-editor-actions';
  const cancel = button('取消', 'msg-action', () => restoreUserBubble(wrapper, original));
  const send = button('发送', 'btn btn-primary btn-sm', () => resendUserMessage(wrapper, sequence, field.value));
  actions.append(cancel, send);
  editor.append(field, actions);
  wrapper.querySelector('.bubble')?.remove();
  wrapper.querySelector('.msg-actions')?.remove();
  wrapper.append(editor);
  field.focus();
  field.setSelectionRange(field.value.length, field.value.length);
}

function restoreUserBubble(wrapper, text) {
  wrapper.replaceChildren();
  const bubble = document.createElement('div');
  bubble.className = 'bubble';
  bubble.textContent = text;
  const actions = document.createElement('div');
  actions.className = 'msg-actions';
  actions.append(button('编辑', 'msg-edit-btn', () => beginEditUserMessage(wrapper)));
  actions.append(button('回退 / 分支', 'msg-edit-btn', () => openMessageCheckpoint(wrapper)));
  wrapper.append(bubble, actions);
}

async function resendUserMessage(wrapper, sequence, content) {
  const text = String(content || '').trim();
  if (!text || !state.sessionId) {
    return;
  }
  const send = wrapper.querySelector('.btn-primary');
  if (send) {
    send.disabled = true;
  }
  closeStream();
  try {
    const response = await apiFetch(`/api/sessions/${encodeURIComponent(state.sessionId)}/resend`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sequence, content: text })
    });
    if (!response.ok) {
      throw new Error(await describeError(response));
    }
    state.cursor = 0;
    await loadHistory();
    if (state.sessionId) {
      connect(state.cursor);
    }
  } catch (error) {
    showNotice('重发失败：' + error.message);
    if (state.sessionId && !state.source) {
      connect(state.cursor);
    }
    restoreUserBubble(wrapper, wrapper.dataset.text || text);
  }
}

/** 工具类型 → 单字符图标，让一屏内能快速扫出「读 / 写 / 命令」。 */
const TOOL_ICONS = {
  read_file: 'R',
  list_dir: 'D',
  search_text: 'S',
  edit_file: 'E',
  create_file: '+',
  run_command: '$'
};

function addToolRow(callId, name, args) {
  const existing = state.toolRows.get(callId);
  if (existing) {
    return existing;
  }

  const row = document.createElement('div');
  row.className = 'tool-row';

  const button = document.createElement('button');
  button.type = 'button';

  const status = document.createElement('span');
  status.className = 'tool-status';
  status.textContent = '…';

  const icon = document.createElement('span');
  icon.className = 'tool-icon';
  icon.textContent = TOOL_ICONS[name] || '·';

  const nameEl = document.createElement('span');
  nameEl.className = 'tool-name';
  nameEl.textContent = name || 'tool';

  const argsEl = document.createElement('span');
  argsEl.className = 'tool-args';
  argsEl.textContent = args || '';

  const metaEl = document.createElement('span');
  metaEl.className = 'tool-meta';
  metaEl.textContent = '';

  button.append(status, icon, nameEl, argsEl, metaEl);

  const body = document.createElement('div');
  body.className = 'tool-body';
  const pre = document.createElement('pre');
  body.append(pre);

  button.addEventListener('click', () => row.classList.toggle('is-open'));

  row.append(button, body);
  const turn = ensureTurnShell();
  turn.list.append(row);
  noteToolStarted(turn, name || 'tool');
  if (!state.replaying) {
    scrollThread();
  }

  let settled = false;
  const handle = {
    element: row,
    setStatus(kind, glyph) {
      status.className = 'tool-status is-' + kind;
      status.textContent = glyph;
      if (!settled && (kind === 'ok' || kind === 'error')) {
        settled = true;
        noteToolSettled(turn);
      }
    },
    setMeta(value) {
      metaEl.textContent = value || '';
    },
    setBody(value) {
      pre.textContent = value || '';
      if (value) {
        metaEl.textContent = metaEl.textContent || '详情';
      }
    }
  };
  state.toolRows.set(callId, handle);
  return handle;
}

/* ---------- 审批 ---------- */

function renderApprovalDock() {
  const entries = [...state.pendingApprovals.entries()];
  if (!entries.length) {
    el.approvalDock.hidden = true;
    el.approvalDock.replaceChildren();
    return;
  }

  const [callId, pending] = entries[0];
  el.approvalDock.hidden = false;
  el.approvalDock.replaceChildren();

  const head = document.createElement('p');
  head.className = 'approval-head';
  head.append(document.createTextNode('需要批准 · '));
  const toolName = document.createElement('span');
  toolName.className = 'tool-name';
  toolName.textContent = pending.name;
  head.append(toolName);
  if (entries.length > 1) {
    head.append(document.createTextNode(` 等 ${entries.length} 项`));
  }

  const args = document.createElement('p');
  args.className = 'approval-args';
  args.textContent = pending.args || '';

  const actions = document.createElement('div');
  actions.className = 'approval-actions';
  const allow = button('批准并执行', 'btn btn-primary', () => resolveApproval(callId, true));
  const deny = button('拒绝', 'btn btn-danger', () => resolveApproval(callId, false));
  actions.append(allow, deny);

  el.approvalDock.append(head, args, actions);
}

async function resolveApproval(callId, approved, scope) {
  const buttons = scope
    ? [...scope.querySelectorAll('button')]
    : [...el.approvalDock.querySelectorAll('button')];
  buttons.forEach((item) => { item.disabled = true; });

  try {
    const response = await apiFetch(`/api/approvals/${encodeURIComponent(callId)}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ approved })
    });
    if (!response.ok) {
      throw new Error(response.status === 404 ? '审批已超时或已处理' : 'HTTP ' + response.status);
    }
  } catch (error) {
    buttons.forEach((item) => { item.disabled = false; });
    showNotice('提交审批失败：' + error.message);
  }
}

/* ---------- 活动面板 ---------- */

const MAX_TIMELINE_EVENTS = 300;

/** 同一次运行里连续的 assistant.delta 合并到一张卡片，避免长任务把面板刷爆。 */
function recordDelta(event, text) {
  if (state.streamCard && state.streamCard.isConnected) {
    state.streamText += text;
    const detail = state.streamCard.querySelector('.event-detail');
    if (detail) {
      detail.textContent = preview(state.streamText, 160);
    }
    return;
  }
  state.streamText = text;
  state.streamCard = record(event, {
    kind: 'run',
    tone: 'text',
    status: '增量',
    title: null,
    detail: preview(text, 160)
  });
}

function record(event, { kind, tone, status, title, detail, foot, actions, callId, resumable }) {
  if (event.type !== 'assistant.delta') {
    state.streamCard = null;
    state.streamText = '';
  }
  state.activityCount += 1;

  const item = document.createElement('li');
  item.className = 'event tone-' + tone;
  item.dataset.kind = kind;

  const head = document.createElement('div');
  head.className = 'event-head';

  const seq = document.createElement('span');
  seq.className = 'event-seq';
  seq.textContent = '#' + event.sequence;

  const type = document.createElement('span');
  type.className = 'event-type';
  type.textContent = event.type;

  const statusEl = document.createElement('span');
  statusEl.className = 'event-status';
  statusEl.textContent = status;

  const time = document.createElement('span');
  time.className = 'event-time';
  time.textContent = formatTime(event.timestamp);

  head.append(seq, type, statusEl, time);
  item.append(head);

  if (title) {
    const titleEl = document.createElement('p');
    titleEl.className = 'event-title';
    titleEl.textContent = title;
    item.append(titleEl);
  }
  if (detail) {
    const detailEl = document.createElement('p');
    detailEl.className = 'event-detail';
    detailEl.textContent = preview(detail, 240);
    item.append(detailEl);
  }
  if (foot) {
    const footEl = document.createElement('p');
    footEl.className = 'event-foot';
    footEl.textContent = foot;
    item.append(footEl);
  }
  if (actions && callId) {
    const box = document.createElement('div');
    box.className = 'approval-actions';
    box.append(
      button('批准', 'btn btn-primary', () => resolveApproval(callId, true, box)),
      button('拒绝', 'btn btn-danger', () => resolveApproval(callId, false, box))
    );
    item.append(box);
  }
  if (resumable) {
    const box = document.createElement('div');
    box.className = 'approval-actions';
    box.append(button('继续执行', 'btn btn-primary', () => continueRun(box)));
    item.append(box);
  }

  el.timeline.append(item);
  while (el.timeline.children.length > MAX_TIMELINE_EVENTS) {
    el.timeline.firstElementChild.remove();
  }
  el.timelineEmpty.hidden = true;
  el.toggleActivity.title = `活动面板 · ${state.activityCount} 条事件 (Ctrl B)`;

  if (state.filter === 'all') {
    item.hidden = false;
  } else {
    item.hidden = item.dataset.kind !== state.filter;
  }
  return item;
}

function onFilterClick(event) {
  const target = event.target.closest('.filter');
  if (!target) {
    return;
  }
  state.filter = target.dataset.filter;
  for (const item of el.filters.querySelectorAll('.filter')) {
    const on = item === target;
    item.classList.toggle('is-on', on);
    item.setAttribute('aria-selected', String(on));
  }
  applyFilter();
}

function applyFilter() {
  for (const item of el.timeline.children) {
    item.hidden = state.filter !== 'all' && item.dataset.kind !== state.filter;
  }
}

function toggleActivity() {
  let visible;
  if (isNarrow()) {
    visible = el.activity.classList.toggle('is-open');
  } else {
    visible = !el.activity.classList.toggle('is-hidden');
  }
  el.toggleActivity.setAttribute('aria-pressed', String(visible));
  el.app.classList.toggle('inspector-collapsed', !visible);
  syncScrim();
  if (visible) {
    el.timeline.scrollTop = el.timeline.scrollHeight;
  }
  syncCommandPolling();
}

function isNarrow() {
  return window.matchMedia('(max-width: 960px)').matches;
}

/* ---------- 活动 / 变更 面板 ---------- */

function onTabClick(event) {
  const tab = event.target.closest('.tab');
  if (!tab) {
    return;
  }
  selectTab(tab.dataset.tab);
}

function selectTab(tab) {
  state.tab = tab;
  for (const item of el.panelTabs.querySelectorAll('.tab')) {
    const on = item.dataset.tab === tab;
    item.classList.toggle('is-on', on);
    item.setAttribute('aria-selected', String(on));
  }
  el.panelActivity.hidden = tab !== 'activity';
  el.panelChanges.hidden = tab !== 'changes';
  el.panelPreview.hidden = tab !== 'preview';
  el.panelCommands.hidden = tab !== 'commands';
  el.filters.hidden = tab !== 'activity';
  if (tab === 'changes') {
    loadChanges();
  }
  syncCommandPolling();
}

/* ---------- 会话命令任务：只在当前可见面板轮询，日志用服务端游标增量读取 ---------- */

function emptyCommandLog() {
  return { text: '', offset: 0, trimmed: 0, truncated: false, eof: false };
}

function commandPanelVisible() {
  return Boolean(state.sessionId && !document.hidden && state.tab === 'commands'
    && (isNarrow() ? el.activity.classList.contains('is-open') : !el.activity.classList.contains('is-hidden')));
}

function stopCommandRequests() {
  clearTimeout(commandView.timer);
  commandView.timer = null;
  commandView.version += 1;
  commandView.controller?.abort();
  commandView.stopController?.abort();
  commandView.controller = null;
  commandView.stopController = null;
  commandView.busy = false;
  commandView.stoppingId = null;
  commandView.loadingLog = false;
}

function resetCommandPanel() {
  stopCommandRequests();
  commandView.tasks = [];
  commandView.selectedId = null;
  commandView.detail = null;
  commandView.log = emptyCommandLog();
  commandView.loadingLog = false;
  commandView.loaded = false;
  el.commandsState.className = 'commands-state';
  el.commandsState.textContent = state.sessionId ? '打开命令面板查看当前会话任务' : '选择会话后可查看命令';
  el.commandsRefresh.disabled = !state.sessionId;
  renderCommandTasks();
  renderCommandDetail();
}

function syncCommandPolling() {
  if (!commandPanelVisible()) {
    stopCommandRequests();
    return;
  }
  if (!commandView.busy && !commandView.timer) refreshCommandTasks(true);
}

function restartCommandPolling() {
  stopCommandRequests();
  refreshCommandTasks(commandPanelVisible());
}

function commandRequestCurrent(sessionId, version) {
  return state.sessionId === sessionId && commandView.version === version;
}

async function commandJson(url, controller, method = 'GET') {
  const request = new AbortController();
  const abort = () => request.abort();
  let timedOut = false;
  if (controller.signal.aborted) request.abort();
  else controller.signal.addEventListener('abort', abort, { once: true });
  const timer = setTimeout(() => { timedOut = true; request.abort(); }, REQUEST_TIMEOUT_MS);
  try {
    const response = await NATIVE_FETCH(url, { method, signal: request.signal });
    if (!response.ok) throw new Error(await describeError(response));
    // 取消和超时必须覆盖响应体读取，不能在收到响应头时就解除。
    return await response.json();
  } catch (error) {
    if (timedOut && error?.name === 'AbortError') throw new Error('请求超时（20 秒）');
    throw error;
  } finally {
    clearTimeout(timer);
    controller.signal.removeEventListener('abort', abort);
  }
}

async function refreshCommandTasks(withLogs = commandPanelVisible()) {
  if (!state.sessionId || document.hidden || commandView.busy || commandView.stopController) return;
  clearTimeout(commandView.timer);
  commandView.timer = null;
  const sessionId = state.sessionId;
  const version = commandView.version;
  const controller = new AbortController();
  commandView.controller = controller;
  commandView.busy = true;
  if (!commandView.loaded) el.commandsState.textContent = '正在连接命令任务…';
  const base = `/api/sessions/${encodeURIComponent(sessionId)}/commands`;
  let catchUpLogs = false;
  try {
    const data = await commandJson(base, controller);
    if (!commandRequestCurrent(sessionId, version)) return;
    commandView.loaded = true;
    commandView.tasks = (Array.isArray(data.tasks) ? data.tasks : [])
      .filter((task) => task && task.taskId && task.sessionId === sessionId)
      .sort((a, b) => String(b.startedAt || '').localeCompare(String(a.startedAt || '')));
    if (!commandView.tasks.some((task) => task.taskId === commandView.selectedId)) {
      commandView.selectedId = commandView.tasks[0]?.taskId || null;
      commandView.log = emptyCommandLog();
      commandView.loadingLog = Boolean(commandView.selectedId);
    }
    commandView.detail = commandView.tasks.find((task) => task.taskId === commandView.selectedId) || null;
    renderCommandTasks();
    renderCommandDetail();
    if (withLogs && commandPanelVisible() && commandView.selectedId) {
      const taskId = commandView.selectedId;
      const taskUrl = `${base}/${encodeURIComponent(taskId)}`;
      renderCommandLog();
      const [task, chunk] = await Promise.all([
        commandJson(taskUrl, controller),
        commandJson(`${taskUrl}/logs?offset=${encodeURIComponent(commandView.log.offset)}&limit=${COMMAND_LOG_CHUNK_LIMIT}`, controller)
      ]);
      if (!commandRequestCurrent(sessionId, version) || commandView.selectedId !== taskId) return;
      if (task.sessionId !== sessionId || chunk.taskId !== taskId) throw new Error('命令任务响应不匹配，请刷新');
      commandView.detail = task;
      commandView.tasks = commandView.tasks.map((item) => item.taskId === taskId ? task : item);
      commandView.log = mergeCommandLog(commandView.log, chunk);
      catchUpLogs = !chunk.eof && Array.from(chunk.text || '').length >= COMMAND_LOG_CHUNK_LIMIT;
      commandView.loadingLog = false;
      renderCommandTasks();
      renderCommandDetail();
    }
    const active = commandView.tasks.filter((task) => isCommandActive(task.status)).length;
    el.commandsState.className = 'commands-state';
    el.commandsState.textContent = commandView.tasks.length
      ? `${commandView.tasks.length} 个任务 · ${active ? `${active} 个运行中` : '已更新'}`
      : '已连接 · 暂无命令任务';
  } catch (error) {
    if (!commandRequestCurrent(sessionId, version) || controller.signal.aborted) return;
    commandView.loadingLog = false;
    el.commandsState.className = 'commands-state is-error';
    el.commandsState.textContent = '连接失败：' + error.message + '。可点击刷新重试。';
    renderCommandLog();
  } finally {
    if (commandRequestCurrent(sessionId, version)) {
      commandView.busy = false;
      commandView.controller = null;
      if (commandPanelVisible()) commandView.timer = setTimeout(() => {
        commandView.timer = null;
        refreshCommandTasks(true);
      }, catchUpLogs ? 50 : 1000);
    }
  }
}

function commandElapsed(task) {
  const start = Date.parse(task.startedAt || '');
  const finish = task.finishedAt ? Date.parse(task.finishedAt) : Date.now();
  return Number.isFinite(start) && Number.isFinite(finish) ? elapsedText(Math.max(0, finish - start)) : '时间未知';
}

function commandDate(value) {
  const date = new Date(value);
  return value && Number.isFinite(date.getTime()) ? date.toLocaleString() : '—';
}

function commandSummary(task) {
  return `${commandStatusLabel(task.status)} · ${task.background ? '后台' : '前台'} · ${commandElapsed(task)}`
    + (task.exitCode != null ? ` · 退出码 ${task.exitCode}` : '');
}

function renderCommandTasks() {
  const active = commandView.tasks.filter((task) => isCommandActive(task.status)).length;
  el.commandsCount.textContent = String(active || commandView.tasks.length);
  el.commandsCount.hidden = commandView.tasks.length === 0;
  el.commandsCount.title = active ? `${active} 个命令运行中` : `${commandView.tasks.length} 个历史命令`;
  el.commandsEmpty.hidden = commandView.tasks.length > 0;
  const existing = new Map([...el.commandsList.children].map((row) => [row.dataset.taskId, row]));
  let index = 0;
  for (const task of commandView.tasks) {
    let row = existing.get(task.taskId);
    if (!row) {
      row = document.createElement('li');
      row.dataset.taskId = task.taskId;
      const item = document.createElement('button');
      item.type = 'button';
      item.className = 'command-task';
      const command = document.createElement('span');
      command.className = 'command-task-command mono';
      const summary = document.createElement('span');
      summary.className = 'command-task-summary';
      const id = document.createElement('span');
      id.className = 'command-task-id mono';
      item.append(command, summary, id);
      item.addEventListener('click', () => selectCommandTask(task.taskId));
      row.append(item);
    }
    existing.delete(task.taskId);
    const item = row.firstElementChild;
    item.classList.toggle('is-selected', task.taskId === commandView.selectedId);
    item.setAttribute('aria-pressed', String(task.taskId === commandView.selectedId));
    item.dataset.status = task.status;
    item.title = `${String(task.command || '')}\n${task.workdir || ''}\n${commandDate(task.startedAt)}`;
    item.children[0].textContent = String(task.command || '（命令内容为空）');
    item.children[1].textContent = commandSummary(task);
    item.children[2].textContent = task.taskId;
    if (el.commandsList.children[index] !== row) {
      el.commandsList.insertBefore(row, el.commandsList.children[index] || null);
    }
    index += 1;
  }
  for (const row of existing.values()) row.remove();
}

function selectCommandTask(taskId) {
  if (commandView.selectedId === taskId) return;
  stopCommandRequests();
  commandView.selectedId = taskId;
  commandView.detail = commandView.tasks.find((task) => task.taskId === taskId) || null;
  commandView.log = emptyCommandLog();
  commandView.loadingLog = true;
  renderCommandTasks();
  renderCommandDetail();
  refreshCommandTasks(true);
}

function renderCommandDetail() {
  const task = commandView.detail;
  el.commandDetail.hidden = !task;
  if (!task) {
    el.commandLog.textContent = '';
    el.commandLog.dataset.trimmed = '0';
    return;
  }
  el.commandTitle.textContent = String(task.command || '命令任务');
  el.commandTitle.title = String(task.command || '');
  el.commandMeta.textContent = `${commandSummary(task)}${task.pid != null ? ` · PID ${task.pid}` : ''}\n`
    + `任务 ${task.taskId}\n目录 ${task.workdir || '—'}\n开始 ${commandDate(task.startedAt)}`
    + (task.finishedAt ? `\n结束 ${commandDate(task.finishedAt)}` : '')
    + `\n超时 ${task.timeoutSeconds > 0 ? `${task.timeoutSeconds} 秒` : '不限时'}`;
  el.commandReason.hidden = !task.reason;
  el.commandReason.textContent = task.reason || '';
  const stopping = commandView.stoppingId === task.taskId || task.status === 'STOPPING';
  el.commandStop.disabled = !isCommandActive(task.status) || stopping;
  el.commandStop.textContent = stopping ? '正在停止…' : '停止命令';
  renderCommandLog();
}

function renderCommandLog() {
  if (!commandView.detail) return;
  const log = commandView.log;
  const stick = isNearBottom(el.commandLog, 80);
  const previousHeight = el.commandLog.scrollHeight;
  const previousTop = el.commandLog.scrollTop;
  const removedPoints = Math.max(0, log.trimmed - Number(el.commandLog.dataset.trimmed || 0));
  if (el.commandLog.textContent !== log.text) {
    const removedHeight = !stick && removedPoints ? commandLogRemovedHeight(removedPoints) : 0;
    el.commandLog.textContent = log.text;
    if (stick) el.commandLog.scrollTop = el.commandLog.scrollHeight;
    else if (removedPoints) el.commandLog.scrollTop = Math.max(0, previousTop - removedHeight);
    else if (el.commandLog.scrollHeight < previousHeight) {
      el.commandLog.scrollTop = Math.max(0, previousTop - previousHeight + el.commandLog.scrollHeight);
    }
  }
  el.commandLog.dataset.trimmed = String(log.trimmed);
  const notes = [];
  if (log.trimmed) notes.push(`界面已省略 ${log.trimmed.toLocaleString()} 个较早字符`);
  if (log.truncated) notes.push('服务端输出已截断');
  if (commandView.loadingLog) notes.push('正在读取日志…');
  else if (isCommandActive(commandView.detail.status)) notes.push(log.text ? '实时更新 · 每秒刷新' : '命令运行中，等待输出');
  else notes.push(log.eof ? (log.text ? '日志已读完' : '命令没有输出') : '正在读取历史输出…');
  el.commandLogNote.textContent = notes.join(' · ');
}

/** 旧日志被裁剪时保留阅读位置；总高度不变也不能让内容悄悄跳到后面的行。 */
function commandLogRemovedHeight(removedPoints) {
  const node = el.commandLog.firstChild;
  if (!node || node.nodeType !== Node.TEXT_NODE) return 0;
  const removedUnits = Array.from(node.textContent).slice(0, removedPoints).join('').length;
  if (removedUnits >= node.textContent.length) return el.commandLog.scrollHeight;
  const range = document.createRange();
  range.setStart(node, removedUnits);
  range.setEnd(node, Math.min(removedUnits + 1, node.textContent.length));
  const contentTop = el.commandLog.getBoundingClientRect().top + el.commandLog.clientTop
    + (parseFloat(getComputedStyle(el.commandLog).paddingTop) || 0);
  return Math.max(0, range.getBoundingClientRect().top - contentTop + el.commandLog.scrollTop);
}

async function stopSelectedCommand() {
  const task = commandView.detail;
  if (!task || !isCommandActive(task.status) || commandView.stoppingId) return;
  // 停止前取消旧的状态读取，避免晚到的 RUNNING 响应覆盖停止结果。
  stopCommandRequests();
  const sessionId = state.sessionId;
  const controller = new AbortController();
  commandView.stopController = controller;
  commandView.stoppingId = task.taskId;
  renderCommandDetail();
  try {
    const updated = await commandJson(`/api/sessions/${encodeURIComponent(sessionId)}/commands/${encodeURIComponent(task.taskId)}/stop`, controller, 'POST');
    if (state.sessionId !== sessionId || controller.signal.aborted) return;
    commandView.tasks = commandView.tasks.map((item) => item.taskId === task.taskId ? updated : item);
    if (commandView.selectedId === task.taskId) commandView.detail = updated;
    renderCommandTasks();
    renderCommandDetail();
  } catch (error) {
    if (state.sessionId === sessionId && !controller.signal.aborted) {
      el.commandsState.className = 'commands-state is-error';
      el.commandsState.textContent = '停止失败：' + error.message;
      showNotice('停止命令失败：' + error.message);
    }
  } finally {
    if (commandView.stopController === controller) {
      commandView.stopController = null;
      commandView.stoppingId = null;
      renderCommandDetail();
      syncCommandPolling();
    }
  }
}

function resetChanges() {
  el.changeCount.hidden = true;
  el.changeCount.textContent = '0';
  el.changesState.textContent = '尚未加载';
  el.changesState.className = 'changes-state';
  el.changesState.title = '';
  el.changesTotals.hidden = true;
  el.changesTotals.replaceChildren();
  el.changeViewBar.hidden = true;
  el.changeFiles.replaceChildren();
  el.changeDiff.replaceChildren();
  el.runChanges.replaceChildren();
  el.runChanges.hidden = true;
  state.changeFiles = [];
  state.changeDiffByPath = new Map();
  state.changeSelected = null;
  state.changeReport = null;
}

function refreshChanges() {
  if (!state.sessionId) {
    return;
  }
  loadChanges({ silent: state.tab !== 'changes' });
}

async function loadChanges(options = {}) {
  if (!state.sessionId) {
    return;
  }
  const silent = options.silent === true;
  if (!silent) {
    el.changesState.textContent = '读取中…';
    el.changesState.className = 'changes-state';
  }
  try {
    const response = await apiFetch(`/api/sessions/${encodeURIComponent(state.sessionId)}/diff`);
    if (!response.ok) {
      throw new Error('HTTP ' + response.status);
    }
    renderChanges(await response.json(), silent);
  } catch (error) {
    if (!silent) {
      el.changesState.textContent = '读取失败：' + error.message;
      el.changesState.className = 'changes-state is-error';
    }
  }
}

function renderChanges(report, silent) {
  const files = Array.isArray(report.files) ? report.files.slice() : [];
  state.changeReport = report;
  el.changeCount.textContent = String(files.length);
  el.changeCount.hidden = files.length === 0;
  renderChangeTotals(files);
  refreshChangeMarkers(files);

  if (silent) {
    // 后台刷新只更新计数：用户正在看的文件和滚动位置别乱跳
    return;
  }

  if (!report.gitRepository) {
    el.changesState.textContent = '当前工作区不是 git 仓库';
    el.changesState.className = 'changes-state';
    el.changesState.title = '';
    state.changeFiles = [];
    state.changeDiffByPath = new Map();
    state.changeSelected = null;
    el.changeFiles.replaceChildren();
    el.changeViewBar.hidden = true;
    el.changeDiff.replaceChildren(changeHint('没有 git 仓库，无法生成 diff。在该目录执行 git init 后即可审阅改动。'));
    return;
  }

  const parsed = report.diff ? parseDiff(report.diff) : [];
  state.changeDiffByPath = new Map(parsed.map((file) => [normalizePath(file.path), file]));
  state.changeFiles = sortChangeFiles(files);

  el.changesState.textContent = files.length ? `${files.length} 个文件有改动` : '工作区干净';
  el.changesState.className = 'changes-state';
  el.changesState.title = report.truncated ? 'diff 过大，已截断；个别文件的明细可能缺失' : '';

  renderRunChanges();
  renderChangeList();

  if (!files.length) {
    el.changeViewBar.hidden = true;
    el.changeDiff.replaceChildren(changeHint('没有未暂存的改动。'));
    return;
  }
  selectChangeFile(pickDefaultChangeFile());
}

/** 本次运行改过的文件排最前，其余按路径排序，和 git status 的阅读顺序接近。 */
/** 刷新后尽量停在原文件上；第一次打开就选本次运行改过的第一个。 */
function pickDefaultChangeFile() {
  const previous = state.changeSelected;
  if (previous && state.changeFiles.some((file) => normalizePath(file.path) === normalizePath(previous))) {
    return previous;
  }
  const runPaths = new Set((state.changedFiles || []).map(normalizePath));
  const run = state.changeFiles.find((file) => runPaths.has(normalizePath(file.path)));
  return (run || state.changeFiles[0]).path;
}

function renderChangeTotals(files) {
  const added = files.reduce((sum, file) => sum + Number(file.added || 0), 0);
  const removed = files.reduce((sum, file) => sum + Number(file.removed || 0), 0);
  el.changesTotals.replaceChildren();
  if (!files.length) {
    el.changesTotals.hidden = true;
    return;
  }
  el.changesTotals.hidden = false;
  el.changesTotals.append(...diffStatNodes(added, removed));
}

/** +x / −y 两个统计节点。每次重新创建，避免同一节点被搬到别处。 */
function diffStatNodes(added, removed) {
  const add = document.createElement('span');
  add.className = 'add';
  add.textContent = '+' + added;
  const del = document.createElement('span');
  del.className = 'del';
  del.textContent = '−' + removed;
  return [add, del];
}

function renderChangeList() {
  el.changeFiles.replaceChildren();
  for (const file of state.changeFiles) {
    el.changeFiles.append(changeRow(file));
  }
}

/** 一行文件：状态字母 + 文件名（目录淡化） + 增删行数。 */
function changeRow(file) {
  const path = normalizePath(file.path);
  const cut = path.lastIndexOf('/');
  const name = cut < 0 ? path : path.slice(cut + 1);
  const dir = cut < 0 ? '' : path.slice(0, cut);

  const row = document.createElement('li');
  row.className = 'change-row';
  row.dataset.path = file.path;
  row.tabIndex = -1;
  row.setAttribute('role', 'option');
  row.setAttribute('aria-selected', 'false');
  row.title = file.path;

  const status = document.createElement('span');
  status.className = 'change-status is-' + statusKind(file.status);
  status.textContent = (String(file.status || 'M').trim().slice(0, 1) || 'M');

  const main = document.createElement('span');
  main.className = 'change-row-main';
  const nameEl = document.createElement('span');
  nameEl.className = 'change-row-name';
  nameEl.textContent = name;
  main.append(nameEl);
  if (dir) {
    const dirEl = document.createElement('span');
    dirEl.className = 'change-row-dir';
    dirEl.textContent = dir;
    main.append(dirEl);
  }

  row.append(status, main);
  const stat = changeStatNode(file);
  if (stat) {
    row.append(stat);
  }
  row.addEventListener('click', () => selectChangeFile(file.path));
  return row;
}

function changeStatNode(file) {
  const added = Number(file.added || 0);
  const removed = Number(file.removed || 0);
  if (!added && !removed) {
    return null;
  }
  const stat = document.createElement('span');
  stat.className = 'change-row-stat';
  const add = document.createElement('span');
  add.className = 'add';
  add.textContent = '+' + added;
  const del = document.createElement('span');
  del.className = 'del';
  del.textContent = '−' + removed;
  stat.append(add, del);
  return stat;
}

/** 选中一个文件：列表高亮 + 顶部文件头 + 只渲染这个文件的 diff。 */
function selectChangeFile(path, options = {}) {
  const target = normalizePath(path);
  const file = state.changeFiles.find((item) => normalizePath(item.path) === target) || null;
  state.changeSelected = file ? file.path : null;

  for (const row of el.changeFiles.querySelectorAll('.change-row')) {
    const on = row.dataset.path === file?.path;
    row.classList.toggle('is-active', on);
    row.setAttribute('aria-selected', String(on));
    row.tabIndex = on ? 0 : -1;
    if (on && options.scroll !== false) {
      row.scrollIntoView({ block: 'nearest' });
    }
  }

  if (!file) {
    el.changeViewBar.hidden = true;
    el.changeDiff.replaceChildren(changeHint('在左侧选一个文件查看它的改动。'));
    return;
  }

  el.changeViewBar.hidden = false;
  el.changeViewPath.textContent = file.path;
  el.changeViewPath.title = file.path;
  el.changeViewStat.replaceChildren();
  const stat = changeStatNode(file);
  if (stat) {
    el.changeViewStat.append(...stat.childNodes);
  }
  state.previewPath = file.path;
  renderSingleFileDiff(file);
}

function renderSingleFileDiff(file) {
  const parsed = state.changeDiffByPath.get(normalizePath(file.path));
  el.changeDiff.scrollTop = 0;
  el.changeDiff.scrollLeft = 0;
  el.changeDiff.replaceChildren();

  if (!parsed || !parsed.lines.length) {
    el.changeDiff.append(changeHint(file.status === '??' || file.status === 'A'
      ? '新增文件没有可对比的旧内容，点右上角去「预览」看完整文件。'
      : '这个文件没有可显示的文本 diff（二进制、仅重命名，或 diff 被截断）。'));
    return;
  }

  const fragment = document.createDocumentFragment();
  for (const line of parsed.lines) {
    fragment.append(diffLineRow(line));
  }
  el.changeDiff.append(fragment);
}

/** 文件列表的键盘导航：↑↓ Home End，选中即渲染对应 diff。 */
function onChangeListKeydown(event) {
  if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
    return;
  }
  const rows = [...el.changeFiles.querySelectorAll('.change-row')];
  if (!rows.length) {
    return;
  }
  event.preventDefault();
  const index = rows.findIndex((row) => row.dataset.path === state.changeSelected);
  let next = index < 0 ? 0 : index;
  if (event.key === 'ArrowDown') {
    next = Math.min(rows.length - 1, next + 1);
  } else if (event.key === 'ArrowUp') {
    next = Math.max(0, next - 1);
  } else if (event.key === 'Home') {
    next = 0;
  } else {
    next = rows.length - 1;
  }
  selectChangeFile(rows[next].dataset.path);
  rows[next].focus();
}

/** 只展示本次运行写过的文件，避免和仓库里的历史改动混在一起；点一下直接看 diff。 */
function renderRunChanges() {
  const files = state.changedFiles || [];
  el.runChanges.replaceChildren();
  if (!files.length) {
    el.runChanges.hidden = true;
    return;
  }
  el.runChanges.hidden = false;

  const label = document.createElement('span');
  label.className = 'run-changes-label';
  label.textContent = `本次运行 ${files.length} 个`;
  el.runChanges.append(label);

  const known = new Set(state.changeFiles.map((file) => normalizePath(file.path)));
  const visible = files.slice(0, 8);
  for (const path of visible) {
    const present = known.has(normalizePath(path));
    const chip = document.createElement('button');
    chip.type = 'button';
    chip.className = 'run-chip' + (present ? '' : ' is-missing');
    chip.textContent = folderName(path);
    chip.title = present ? path + '\n点击查看 diff' : path + '\n不在未暂存的改动里';
    chip.addEventListener('click', () => {
      if (present) {
        selectChangeFile(path);
      } else {
        showNotice('这个文件不在当前未暂存改动里，可能已提交或被忽略。');
      }
    });
    el.runChanges.append(chip);
  }
  if (files.length > visible.length) {
    const more = document.createElement('span');
    more.className = 'run-changes-label';
    more.textContent = `+${files.length - visible.length}`;
    el.runChanges.append(more);
  }
}

/** 单行 diff：双行号（旧/新）+ 代码；加/删/上下文由 kind 决定配色。 */
function diffLineRow(line) {
  const row = document.createElement('div');
  row.className = 'diff-line is-' + line.kind;

  const oldNo = document.createElement('span');
  oldNo.className = 'n is-old';
  oldNo.textContent = line.old === '' ? '' : String(line.old);
  oldNo.setAttribute('aria-hidden', 'true');

  const newNo = document.createElement('span');
  newNo.className = 'n is-new';
  newNo.textContent = line.next === '' ? '' : String(line.next);
  newNo.setAttribute('aria-hidden', 'true');

  const code = document.createElement('code');
  code.textContent = line.text === '' ? ' ' : line.text;
  row.append(oldNo, newNo, code);
  return row;
}

function changeHint(text) {
  const node = document.createElement('div');
  node.className = 'change-empty';
  node.textContent = text;
  return node;
}

/* ---------- 工作区选择 ---------- */

async function openPicker(options = {}) {
  // 项目弹窗借用同一个目录浏览器：选完把路径交回去，而不是直接开对话
  state.pickerOnPick = typeof options.onPick === 'function' ? options.onPick : null;
  el.pickerConfirm.textContent = state.pickerOnPick ? '选择此文件夹' : '在此工作区开始';
  el.picker.hidden = false;
  el.pickerError.hidden = true;
  el.pickerConfirm.disabled = true;
  await loadRoots();
  const start = state.workspace || localStorage.getItem(LAST_WORKSPACE_KEY) || '';
  await browseTo(start);
  if (options.firstRun && !state.pickerSelection) {
    el.pickerSelected.textContent = '尚未选择目录';
  }
}

function closePicker() {
  el.picker.hidden = true;
}

async function loadRoots() {
  try {
    const response = await apiFetch('/api/workspaces/roots');
    if (!response.ok) {
      return;
    }
    const roots = await response.json();
    el.pickerRoots.replaceChildren();
    for (const root of roots) {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'root-item';
      button.textContent = root.label;
      button.title = root.path;
      button.addEventListener('click', () => browseTo(root.path));
      el.pickerRoots.append(button);
    }
  } catch (error) {
    console.warn('[roots]', error);
  }
}

async function browseTo(path) {
  el.pickerError.hidden = true;
  try {
    const query = path ? '?path=' + encodeURIComponent(path) : '';
    const response = await apiFetch('/api/workspaces/browse' + query);
    if (!response.ok) {
      throw new Error(await describeError(response));
    }
    const data = await response.json();
    state.pickerPath = data.path;
    state.pickerParent = data.parent;
    state.pickerSelection = data.path;
    el.pickerPath.value = data.path;
    renderPickerList(data);
    updatePickerSelection();
  } catch (error) {
    el.pickerError.textContent = error.message;
    el.pickerError.hidden = false;
  }
}

/** 把错误响应翻译成一句人话：优先服务端给的 message/detail，其次 404 与状态码兜底。 */
async function describeError(response) {
  let body = null;
  try {
    body = await response.json();
  } catch (ignored) {
    // 非 JSON 错误体（例如网关返回的 HTML）
  }
  return errorText(response, body);
}

function renderPickerList(data) {
  el.pickerList.replaceChildren();
  const directories = data.directories || [];
  if (!directories.length) {
    const empty = document.createElement('li');
    empty.className = 'dir-item';
    empty.textContent = '（没有子目录，可以直接选择当前目录）';
    empty.style.cursor = 'default';
    empty.style.color = 'var(--dim)';
    el.pickerList.append(empty);
    return;
  }
  for (const dir of directories) {
    const item = document.createElement('li');
    item.className = 'dir-item';
    item.dataset.path = dir.path;

    const name = document.createElement('span');
    name.className = 'dir-name';
    name.textContent = dir.name;

    const enter = document.createElement('button');
    enter.type = 'button';
    enter.className = 'dir-enter';
    enter.textContent = '进入';
    enter.addEventListener('click', (event) => {
      event.stopPropagation();
      browseTo(dir.path);
    });

    item.append(name, enter);
    item.addEventListener('click', () => {
      state.pickerSelection = dir.path;
      updatePickerSelection();
    });
    item.addEventListener('dblclick', () => browseTo(dir.path));
    el.pickerList.append(item);
  }
}

function updatePickerSelection() {
  el.pickerSelected.textContent = state.pickerSelection || '尚未选择目录';
  el.pickerConfirm.disabled = !state.pickerSelection;
  for (const item of el.pickerList.querySelectorAll('.dir-item')) {
    item.classList.toggle('is-on', item.dataset.path === state.pickerSelection);
  }
  const roots = [...el.pickerRoots.querySelectorAll('.root-item')];
  let best = null;
  for (const root of roots) {
    if (isUnder(state.pickerPath, root.title) && (!best || root.title.length > best.title.length)) {
      best = root;
    }
  }
  roots.forEach((root) => root.classList.toggle('is-on', root === best));
}

function isUnder(path, root) {
  if (!path || !root) {
    return false;
  }
  const normalize = (value) => value.toLowerCase().replace(/\//g, '\\');
  const target = normalize(path);
  const base = normalize(root);
  return target === base || target.startsWith(base.endsWith('\\') ? base : base + '\\');
}

function pickerUp() {
  if (state.pickerParent) {
    browseTo(state.pickerParent);
  }
}

function confirmPicker() {
  const target = state.pickerSelection;
  if (!target) {
    return;
  }
  closePicker();
  startSession(target);
}

/* ---------- 输入 ---------- */

function bindComposer() {
  el.input.addEventListener('keydown', (event) => {
    if (onComposerKeydown(event)) {
      return;
    }
    if (handleInputHistoryKeydown(event)) {
      return;
    }
    const submit = (event.ctrlKey || event.metaKey) && event.key === 'Enter';
    if ((event.key === 'Enter' && !event.shiftKey && !event.isComposing) || submit) {
      event.preventDefault();
      el.composer.requestSubmit();
    }
  });
  el.composer.addEventListener('submit', sendMessage);
  // 模型设置里的输入框在表单内：回车不能触发发消息，改为保存
  el.modelMenu.addEventListener('keydown', (event) => {
    if (event.key !== 'Enter') {
      return;
    }
    event.preventDefault();
    saveModel();
  });
}

function onPromptClick(event) {
  const target = event.target.closest('button[data-prompt]');
  if (!target) {
    return;
  }
  el.input.value = target.dataset.prompt;
  autoGrow();
  el.input.focus();
}

async function sendMessage(event) {
  event.preventDefault();
  const content = el.input.value.trim();
  if (state.queueEdit) {
    await saveQueuedEdit(content);
    return;
  }
  const attachments = state.attachments.slice();
  if (!content && !attachments.length) {
    return;
  }
  el.input.value = '';
  autoGrow();
  setAttachments([]);
  // 只有附件没有文字时也要有正文，服务端和模型都需要一句话说明
  await submitMessage(content || attachmentSummary(attachments), attachments);
}

/** 提交一条用户消息并启动运行；输入框与「继续」按钮共用。 */
async function submitMessage(content, attachments = []) {
  if ((!content && !attachments.length) || !state.sessionId) {
    return;
  }
  if (content.trim()) {
    rememberInput(content);
  }

  // 运行中先排队，当前任务完成后才发出；等待时可以改、可以删
  const wasRunning = state.running;
  const taskRequirements = !wasRunning ? taskDraftRequirements() : null;
  if (!wasRunning) {
    appendUserMessage(content || attachmentSummary(attachments));
    state.pendingInputs.push(content);
    renameSessionIfDefault(content);
    state.running = true;
    state.runId = null;
    state.lastRunId = null;
    state.changedFiles = [];
    setRunState('running', '发送中');
  } else {
    state.queue.push(content);
    renderQueue();
    setRunState('running', '已排队');
  }
  syncComposer();
  scrollThread();

  try {
    const response = await apiFetch(`/api/sessions/${encodeURIComponent(state.sessionId)}/messages`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        content,
        ...(taskRequirements ? { task: taskRequirements } : {}),
        attachments: attachments.map((item) => ({
          name: item.name,
          mimeType: item.mimeType,
          dataBase64: item.dataBase64
        }))
      })
    });
    if (response.status === 202) {
      await loadQueue();
      return;
    }
    if (!response.ok) {
      throw new Error(await describeError(response));
    }
    const started = await response.json();
    if (!wasRunning) {
      el.taskDraftConstraints.value = '';
      el.taskDraftAcceptance.value = '';
      el.taskDraft.open = false;
    }
    state.runId = started.runId || null;
    scheduleTaskRefresh();
    syncComposer();
  } catch (error) {
    if (!wasRunning) {
      state.running = false;
      state.pendingInputs.pop();
      syncComposer();
      setRunState('error', '发送失败');
    } else {
      const index = state.queue.lastIndexOf(content);
      if (index >= 0) {
        state.queue.splice(index, 1);
        renderQueue();
      }
    }
    if (wasRunning && attachments.length) {
      // 运行中追加图片会被服务端拒绝，把附件放回输入区，避免白丢
      setAttachments(attachments);
    }
    if (!el.input.value.trim()) { el.input.value = content; autoGrow(); }
    showNotice('发送失败：' + error.message);
  }
}

function inputHistoryStorageKey(sessionId = state.sessionId) {
  return sessionId ? `pilot.inputHistory.${sessionId}` : null;
}

function loadInputHistory(sessionId) {
  state.inputHistory = [];
  state.inputHistoryIndex = -1;
  state.inputHistoryDraft = '';
  try {
    const stored = JSON.parse(localStorage.getItem(inputHistoryStorageKey(sessionId)) || '[]');
    state.inputHistory = Array.isArray(stored) ? stored.filter((item) => typeof item === 'string') : [];
  } catch {
    state.inputHistory = [];
  }
}

function rememberInput(input) {
  const value = input.trim();
  if (!value || !state.sessionId) return;
  const history = state.inputHistory.filter((item) => item !== value);
  history.push(value);
  state.inputHistory = history.slice(-100);
  state.inputHistoryIndex = -1;
  state.inputHistoryDraft = '';
  try {
    localStorage.setItem(inputHistoryStorageKey(), JSON.stringify(state.inputHistory));
  } catch (error) {
    console.warn('[input-history]', error);
  }
}

function handleInputHistoryKeydown(event) {
  if (event.key !== 'ArrowUp' && event.key !== 'ArrowDown'
      || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) {
    return false;
  }
  const atStart = el.input.selectionStart === 0;
  const atEnd = el.input.selectionEnd === el.input.value.length;
  const browsing = state.inputHistoryIndex >= 0;
  if ((event.key === 'ArrowUp' && !atStart) || (event.key === 'ArrowDown' && !atEnd)) {
    return false;
  }
  if (!state.inputHistory.length || (event.key === 'ArrowUp' && !browsing && el.input.value.includes('\\n'))) {
    return false;
  }

  event.preventDefault();
  if (!browsing) {
    if (event.key === 'ArrowDown') return true;
    state.inputHistoryDraft = el.input.value;
    state.inputHistoryIndex = state.inputHistory.length;
  }
  const next = state.inputHistoryIndex + (event.key === 'ArrowUp' ? -1 : 1);
  if (next < 0) return true;
  if (next >= state.inputHistory.length) {
    state.inputHistoryIndex = -1;
    el.input.value = state.inputHistoryDraft;
  } else {
    state.inputHistoryIndex = next;
    el.input.value = state.inputHistory[next];
  }
  el.input.setSelectionRange(el.input.value.length, el.input.value.length);
  onComposerInput();
  return true;
}

/** 点击发送与 Enter 语义一致：运行中排队，空闲时开始新任务。 */
function onSendButtonClick() {
  el.composer.requestSubmit();
}

async function steerCurrentTask() {
  const content = el.input.value.trim();
  if (!state.running || !state.sessionId || state.steering || !content) {
    if (!content) { showNotice('先在输入框写下如何纠正当前任务。'); el.input.focus(); }
    return;
  }
  if (state.attachments.length || state.queueEdit) {
    showNotice('纠正当前任务只接受纯文本；请先完成队列编辑或移除附件。');
    return;
  }
  const sessionId = state.sessionId;
  const runId = state.runId;
  state.steering = true;
  syncComposer();
  try {
    const response = await apiFetch(`/api/sessions/${encodeURIComponent(sessionId)}/steer`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ content, runId })
    });
    if (!response.ok) throw new Error(await describeError(response));
    if (state.sessionId !== sessionId) return;
    rememberInput(content);
    if (el.input.value.trim() === content) { el.input.value = ''; autoGrow(); }
    showNotice('纠正已收到，将在下一安全步骤生效；当前已经开始的工具操作会先结束。');
    scheduleTaskRefresh();
  } catch (error) {
    if (state.sessionId === sessionId) showNotice('纠正未发送：' + error.message);
  } finally {
    if (state.sessionId === sessionId) { state.steering = false; syncComposer(); }
  }
}

async function pauseCurrentTask() {
  if (!state.running || state.pausing) return;
  const sessionId = state.sessionId;
  if (!state.runId) await refreshActiveRun();
  if (state.sessionId !== sessionId || !state.runId) return;
  const runId = state.runId;
  state.pausing = true;
  syncComposer();
  try {
    const response = await apiFetch(`/api/runs/${encodeURIComponent(runId)}/pause`, { method: 'POST' });
    if (!response.ok) throw new Error(await describeError(response));
    if (state.sessionId === sessionId) showNotice('已请求暂停；任务目标与等待消息会保留，暂停完成后可以续跑。');
  } catch (error) {
    if (state.sessionId !== sessionId) return;
    showNotice('暂停失败：' + error.message);
    state.pausing = false;
    syncComposer();
  }
}

async function cancelRun() {
  if (!state.runId) {
    // 刷新后本地可能还没拿到 runId，向服务端确认一次再决定能不能停
    await refreshActiveRun();
  }
  if (!state.runId) {
    showNotice('当前没有正在运行的任务。');
    return;
  }
  el.send.disabled = true;
  try {
    const response = await apiFetch(`/api/runs/${encodeURIComponent(state.runId)}/cancel`, { method: 'POST' });
    if (!response.ok) {
      throw new Error(await describeError(response));
    }
    showNotice('已请求取消，当前步骤结束后停止。');
  } catch (error) {
    showNotice('取消失败：' + error.message);
  } finally {
    el.send.disabled = false;
  }
}

/**
 * 继续上一次未完成的任务：走 /continue，不伪造「继续」这条用户消息，
 * 服务端用已经落盘的对话记忆接着跑，进程重启过也能接上。
 */
async function continueRun(box) {
  if (state.running || !state.sessionId) {
    return;
  }
  if (state.taskDirty && !(await saveTaskContract())) return;
  if (state.running) return;
  const buttons = [...box.querySelectorAll('button')];
  buttons.forEach((item) => { item.disabled = true; });
  try {
    const response = await apiFetch(`/api/sessions/${encodeURIComponent(state.sessionId)}/continue`, {
      method: 'POST'
    });
    if (!response.ok) {
      throw new Error(await describeError(response));
    }
    const data = await response.json();
    state.runId = data.runId || state.runId;
    state.lastRunId = data.runId || state.lastRunId;
    state.running = true;
    startRunClock(Date.now());
    setRunState('run', '运行中');
    syncComposer();
  } catch (error) {
    showNotice('继续失败：' + error.message);
    buttons.forEach((item) => { item.disabled = false; });
  }
}

async function refreshActiveRun() {
  if (!state.sessionId) {
    return null;
  }
  const sessionId = state.sessionId;
  try {
    const response = await apiFetch(`/api/sessions/${encodeURIComponent(sessionId)}/runs/active`);
    if (!response.ok) {
      return null;
    }
    const data = await response.json();
    if (state.sessionId !== sessionId) return null;
    if (data.runId) {
      state.runId = data.runId;
      state.running = true;
      startRunClock(Date.now());
      syncComposer();
    }
    return data.runId || null;
  } catch (error) {
    console.warn('[active]', error);
    return null;
  }
}

async function createWorkspaceBranch() {
  if (!state.sessionId || state.running) {
    return;
  }
  const name = window.prompt('新 Git 分支名称（当前未提交改动会保留在工作区）');
  if (!name || !name.trim()) {
    return;
  }
  el.branchCreate.disabled = true;
  try {
    const response = await apiFetch(`/api/sessions/${encodeURIComponent(state.sessionId)}/branch`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: name.trim() })
    });
    if (!response.ok) {
      throw new Error(await describeError(response));
    }
    const branch = await response.json();
    showNotice(`已切换到 Git 分支 ${branch.name}，当前未提交改动已保留。`);
    loadChanges();
  } catch (error) {
    showNotice('创建分支失败：' + error.message);
  } finally {
    el.branchCreate.disabled = false;
  }
}

async function rollbackRun() {
  if (!state.lastRunId || state.running) {
    return;
  }
  const confirmed = window.confirm('把本次运行改过的文件还原到运行前？新建的文件会被删除。');
  if (!confirmed) {
    return;
  }
  el.changesRollback.disabled = true;
  try {
    const response = await apiFetch(`/api/runs/${encodeURIComponent(state.lastRunId)}/rollback`, { method: 'POST' });
    if (response.status === 404) {
      showNotice('这次运行没有产生文件改动，无需回滚。');
      return;
    }
    if (!response.ok) {
      throw new Error(await describeError(response));
    }
    const report = await response.json();
    const conflicts = report.conflicts || [];
    if (conflicts.length) {
      showNotice(`检测到回退后又被修改的文件，未执行回退：${conflicts.join('、')}`);
      return;
    }
    const failed = (report.failed || []).length;
    showNotice(`已回滚：还原 ${(report.restored || []).length} 个、删除 ${(report.deleted || []).length} 个`
      + (failed ? `、失败 ${failed} 个` : ''));
    loadChanges();
  } catch (error) {
    showNotice('回滚失败：' + error.message);
  } finally {
    el.changesRollback.disabled = false;
    syncComposer();
  }
}

function autoGrow() {
  el.input.style.height = 'auto';
  el.input.style.height = Math.min(el.input.scrollHeight, 180) + 'px';
}

/* ---------- 命令面板 ---------- */

function openPalette() {
  el.palette.hidden = false;
  el.paletteInput.value = '';
  state.paletteIndex = 0;
  renderPalette('');
  el.paletteInput.focus();
}

function closePalette() {
  el.palette.hidden = true;
  el.paletteItems = [];
}

function renderPalette(query) {
  const items = filterPaletteActions(query);
  state.paletteItems = items;
  state.paletteIndex = Math.min(state.paletteIndex, Math.max(items.length - 1, 0));

  el.paletteList.replaceChildren();
  if (!items.length) {
    const empty = document.createElement('li');
    empty.className = 'palette-item is-empty';
    empty.textContent = '没有匹配的命令';
    el.paletteList.append(empty);
    return;
  }

  items.forEach((item, index) => {
    const li = document.createElement('li');
    li.className = 'palette-item' + (index === state.paletteIndex ? ' is-on' : '');
    li.setAttribute('role', 'option');
    const label = document.createElement('span');
    label.textContent = item.label;
    const hint = document.createElement('span');
    hint.className = 'palette-hint';
    hint.textContent = item.hint || '';
    li.append(label, hint);
    li.addEventListener('click', () => runPaletteItem(item));
    li.addEventListener('mouseenter', () => {
      state.paletteIndex = index;
      renderPalette(query);
    });
    el.paletteList.append(li);
  });
}

function runPaletteItem(item) {
  closePalette();
  COMMAND_ACTIONS[item.action]?.();
}

function onPaletteKeydown(event) {
  if (event.key === 'Escape') {
    event.preventDefault();
    closePalette();
    return;
  }
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault();
    const step = event.key === 'ArrowDown' ? 1 : -1;
    const total = state.paletteItems.length;
    if (!total) {
      return;
    }
    state.paletteIndex = (state.paletteIndex + step + total) % total;
    renderPalette(el.paletteInput.value);
    return;
  }
  if (event.key === 'Enter') {
    event.preventDefault();
    const item = state.paletteItems[state.paletteIndex];
    if (item) {
      runPaletteItem(item);
    }
  }
}

function onGlobalKeydown(event) {
  const mod = event.ctrlKey || event.metaKey;
  if (mod && event.key === ',') {
    event.preventDefault();
    el.settings.hidden ? openSettings() : closeSettings();
    return;
  }
  if (mod && event.key.toLowerCase() === 'k') {
    event.preventDefault();
    el.palette.hidden ? openPalette() : closePalette();
    return;
  }
  if (mod && event.key.toLowerCase() === 'n') {
    event.preventDefault();
    openPicker();
    return;
  }
  if (mod && (event.key.toLowerCase() === 'b' || event.key.toLowerCase() === 'j')) {
    event.preventDefault();
    toggleActivity();
    return;
  }
  if (event.key === 'Escape') {
    if (!el.settings.hidden) {
      closeSettings();
      return;
    }
    if (!el.permMenu.hidden) {
      togglePermissionMenu(false);
      return;
    }
    if (!el.modelMenu.hidden) {
      toggleModelMenu(false);
      return;
    }
    if (!el.picker.hidden) {
      closePicker();
    } else if (!el.palette.hidden) {
      closePalette();
    } else {
      closeOverlays();
    }
  }
}

/* ---------- 布局 ---------- */

function toggleSidebar() {
  if (isNarrow()) {
    el.app.classList.toggle('sidebar-open');
    syncScrim();
    return;
  }
  el.app.classList.toggle('sidebar-collapsed');
}

function closeSidebar() {
  if (isNarrow()) {
    el.app.classList.remove('sidebar-open');
    syncScrim();
  }
}

function syncScrim() {
  const drawerOpen = el.app.classList.contains('sidebar-open')
    || el.activity.classList.contains('is-open');
  el.scrim.hidden = !(isNarrow() && drawerOpen);
}

function closeOverlays() {
  closeSidebar();
  el.activity.classList.remove('is-open');
  el.toggleActivity.setAttribute('aria-pressed', String(!el.activity.classList.contains('is-hidden')));
  syncScrim();
  syncCommandPolling();
  if (!el.palette.hidden) {
    closePalette();
  }
}

/* ---------- 视图状态 ---------- */

function setComposerEnabled(enabled) {
  state.composerEnabled = enabled;
  syncComposer();
}

/** 发送、纠正和暂停分别拥有明确入口。 */
function syncComposer() {
  const running = state.running;
  const editing = Boolean(state.queueEdit);
  el.send.classList.remove('is-running');
  el.send.setAttribute('aria-label', editing ? '保存排队消息' : running ? '排队下一件事' : '发送任务');
  el.send.title = editing ? '保存排队消息 (Enter)' : running ? '排队下一件事 (Enter)' : '发送任务 (Enter)';
  el.send.disabled = (!running && !state.composerEnabled) || state.steering;
  el.sendModeLabel.textContent = editing ? '保存排队消息' : running ? '排队下一件事' : '发送任务';
  el.changesRollback.hidden = !(state.lastRunId && !running);
  el.branchCreate.disabled = !state.sessionId || running;
  el.taskLiveControls.hidden = !running;
  el.taskSteer.disabled = state.steering || editing || state.pausing;
  el.taskSteer.textContent = state.steering ? '发送纠正中…' : '立即纠正当前';
  el.taskPause.disabled = state.pausing;
  el.taskPause.textContent = state.pausing ? '正在暂停…' : '暂停当前';
  el.taskCancel.disabled = state.pausing;
  el.taskDraft.hidden = running || editing;
  if (!running) state.pausing = false;
  el.input.placeholder = editing ? '正在编辑排队的消息… Enter 保存' : running
    ? '下一件事用 Enter 排队；纠正当前方向请点“立即纠正当前”'
    : '描述新任务，Enter 发送；续跑当前任务请点“继续当前任务”';
  syncTaskControls();
  renderQueueEditBar();
}

/* ---------- 持久任务目标、约束与验收 ---------- */

function taskDraftRequirements() {
  const lines = (value) => value.split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
  const constraints = lines(el.taskDraftConstraints.value);
  const acceptance = lines(el.taskDraftAcceptance.value).map((text) => ({ text, checked: false, evidence: '' }));
  return constraints.length || acceptance.length ? { constraints, acceptance } : null;
}

function scheduleTaskRefresh() {
  clearTimeout(state.taskRefreshTimer);
  const sessionId = state.sessionId;
  state.taskRefreshTimer = setTimeout(() => {
    if (state.sessionId === sessionId) loadTaskContract();
  }, 300);
}

async function loadTaskContract() {
  const sessionId = state.sessionId;
  if (!sessionId) return;
  const version = ++state.taskLoadVersion;
  try {
    const response = await apiFetch(`/api/sessions/${encodeURIComponent(sessionId)}/task`);
    if (!response.ok) throw new Error(await describeError(response));
    const text = await response.text();
    const task = text.trim() ? JSON.parse(text) : null;
    if (state.sessionId !== sessionId || version !== state.taskLoadVersion) return;
    // 后台刷新不覆盖用户尚未保存的验收记录；新任务或运行状态变化仍需展示。
    if (state.taskDirty && !state.running && task?.taskId === state.task?.taskId) return;
    state.task = task;
    state.taskDirty = false;
    renderTaskContract();
  } catch (error) {
    if (state.sessionId === sessionId) {
      el.taskHint.textContent = '任务要求读取失败：' + error.message;
    }
  }
}

function renderTaskContract() {
  const task = state.task;
  el.taskContract.hidden = !task;
  el.taskAcceptance.replaceChildren();
  el.taskCorrections.replaceChildren();
  if (!task) { syncTaskControls(); return; }
  const labels = { ACTIVE: '执行中', PAUSED: '已暂停', INTERRUPTED: '已中断',
    AWAITING_REVIEW: '待验收', COMPLETED: '已验收', FAILED: '待处理' };
  const items = Array.isArray(task.acceptance) ? task.acceptance : [];
  const checked = items.filter((item) => item.checked).length;
  const status = String(task.status || '').toUpperCase();
  el.taskSummary.textContent = `${labels[status] || status || '已保存'} · ${items.length ? `验收 ${checked}/${items.length}` : '未设置验收条件'}`;
  el.taskGoal.textContent = task.originalGoal || '';
  el.taskConstraints.value = (task.constraints || []).join('\n');
  for (const item of items) appendTaskCriterion(item);
  if (!items.length) {
    const empty = document.createElement('p');
    empty.className = 'task-help task-acceptance-empty';
    empty.textContent = '还没有明确的验收条件，可在暂停后添加。';
    el.taskAcceptance.append(empty);
  }
  const corrections = Array.isArray(task.corrections) ? task.corrections : [];
  el.taskCorrections.hidden = !corrections.length;
  if (corrections.length) {
    const title = document.createElement('p');
    title.className = 'task-field-label';
    title.textContent = '当前任务的纠正记录';
    const list = document.createElement('ol');
    for (const correction of corrections) {
      const item = document.createElement('li');
      item.textContent = typeof correction === 'string' ? correction : correction.content || '';
      list.append(item);
    }
    el.taskCorrections.append(title, list);
  }
  el.taskHint.textContent = state.running ? '运行中可查看要求；修改当前方向请使用“立即纠正当前”。' : '目标和要求已保存，继续任务时会重新提供给模型。';
  syncTaskControls();
}

function appendTaskCriterion(item) {
  el.taskAcceptance.querySelector('.task-acceptance-empty')?.remove();
  const row = document.createElement('div');
  row.className = 'task-criterion';
  const check = document.createElement('input');
  check.type = 'checkbox';
  check.className = 'task-criterion-checked';
  check.checked = item.checked === true;
  check.setAttribute('aria-label', '此验收条件已验证');
  const fields = document.createElement('div');
  const text = document.createElement('input');
  text.type = 'text';
  text.className = 'task-criterion-text';
  text.value = item.text || '';
  text.placeholder = '可核对的完成条件';
  text.setAttribute('aria-label', '验收条件');
  const evidence = document.createElement('textarea');
  evidence.className = 'task-criterion-evidence';
  evidence.rows = 1;
  evidence.value = item.evidence || '';
  evidence.placeholder = '验证证据，例如测试命令与结果、复现步骤';
  evidence.setAttribute('aria-label', '验证证据');
  const remove = button('移除', 'msg-action', () => { row.remove(); markTaskDirty(); });
  for (const field of [check, text, evidence]) field.addEventListener('input', markTaskDirty);
  fields.append(text, evidence);
  row.append(check, fields, remove);
  el.taskAcceptance.append(row);
  syncTaskControls();
}

function markTaskDirty() {
  state.taskDirty = true;
  el.taskHint.textContent = '尚未保存。保存后，继续任务时会使用这些要求。';
  syncTaskControls();
}

function syncTaskControls() {
  const locked = state.running || state.taskBusy;
  for (const field of el.taskContract.querySelectorAll('input, textarea, button')) field.disabled = locked;
  el.taskSave.disabled = locked || !state.taskDirty;
  el.taskSave.textContent = state.taskBusy ? '保存中…' : '保存要求与验收记录';
  const status = String(state.task?.status || '').toUpperCase();
  el.taskResumeControls.hidden = state.running || !state.task
    || !['PAUSED', 'INTERRUPTED', 'FAILED', 'AWAITING_REVIEW', 'ACTIVE'].includes(status);
  el.taskResume.disabled = state.taskBusy;
}

async function saveTaskContract() {
  if (state.running || state.taskBusy || !state.task || !state.sessionId) return false;
  const acceptance = [...el.taskAcceptance.querySelectorAll('.task-criterion')].map((row) => ({
    text: row.querySelector('.task-criterion-text').value.trim(),
    checked: row.querySelector('.task-criterion-checked').checked,
    evidence: row.querySelector('.task-criterion-evidence').value.trim()
  }));
  if (acceptance.some((item) => !item.text || (item.checked && !item.evidence))) {
    el.taskHint.textContent = '请填写每条验收条件；勾选“已验证”时还需填写验证证据。';
    return false;
  }
  const sessionId = state.sessionId;
  state.taskBusy = true;
  syncTaskControls();
  try {
    const response = await apiFetch(`/api/sessions/${encodeURIComponent(sessionId)}/task`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        constraints: el.taskConstraints.value.split(/\r?\n/).map((line) => line.trim()).filter(Boolean), acceptance
      })
    });
    if (!response.ok) throw new Error(await describeError(response));
    const task = await response.json();
    if (state.sessionId !== sessionId) return false;
    state.task = task;
    state.taskDirty = false;
    renderTaskContract();
    await refreshActiveRun();
    if (state.sessionId !== sessionId) return false;
    scheduleTaskRefresh();
    el.taskHint.textContent = '要求与验收记录已保存。';
    return true;
  } catch (error) {
    el.taskHint.textContent = '保存失败：' + error.message;
    return false;
  } finally {
    if (state.sessionId === sessionId) { state.taskBusy = false; syncTaskControls(); }
  }
}

async function loadQueue() {
  if (!state.sessionId || !el.messageQueue) {
    return;
  }
  const sessionId = state.sessionId;
  try {
    const response = await apiFetch(`/api/sessions/${encodeURIComponent(sessionId)}/queue`);
    if (!response.ok || state.sessionId !== sessionId) {
      return;
    }
    const data = await response.json();
    state.queue = Array.isArray(data.items) ? data.items : [];
    renderQueue();
  } catch (error) {
    console.warn('[queue]', error);
  }
}

function renderQueue() {
  if (!el.messageQueue) {
    return;
  }
  const items = state.queue || [];
  el.messageQueue.replaceChildren();
  el.messageQueue.hidden = items.length === 0;
  if (!items.length) {
    if (state.queueEdit) {
      el.input.value = state.queueEdit.previous || '';
      autoGrow();
      state.queueEdit = null;
      syncComposer();
    } else {
      renderQueueEditBar();
    }
    return;
  }
  if (state.queueEdit && state.queueEdit.index >= items.length) {
    const previous = state.queueEdit.previous;
    state.queueEdit = null;
    if (el.input && previous != null) {
      el.input.value = previous;
      autoGrow();
    }
  }

  const collapsed = state.queueCollapsed === true;
  const head = document.createElement('button');
  head.type = 'button';
  head.className = 'queue-head';
  head.setAttribute('aria-expanded', String(!collapsed));
  const chevron = document.createElement('span');
  chevron.className = 'queue-chevron' + (collapsed ? '' : ' is-open');
  chevron.textContent = '›';
  const title = document.createElement('span');
  title.className = 'queue-title';
  title.textContent = items.length === 1 ? '1 条排队' : `${items.length} 条排队`;
  head.append(chevron, title);
  head.addEventListener('click', () => {
    state.queueCollapsed = !state.queueCollapsed;
    renderQueue();
  });
  el.messageQueue.append(head);
  if (collapsed) {
    return;
  }

  items.forEach((text, index) => {
    const row = document.createElement('div');
    row.className = 'queue-item' + (state.queueEdit && state.queueEdit.index === index ? ' is-editing' : '');

    const dot = document.createElement('span');
    dot.className = 'queue-dot';
    dot.setAttribute('aria-hidden', 'true');

    const body = document.createElement('button');
    body.type = 'button';
    body.className = 'queue-text';
    body.textContent = text;
    body.title = '编辑这条排队消息';
    body.addEventListener('click', () => beginQueueEdit(index));

    const actions = document.createElement('div');
    actions.className = 'queue-actions';
    const up = iconButton('往前排', queueUpIcon(), () => moveQueuedItem(index));
    up.disabled = index === 0;
    const remove = iconButton('删除', queueTrashIcon(), () => removeQueuedItem(index));
    actions.append(up, remove);
    row.append(dot, body, actions);
    el.messageQueue.append(row);
  });
}

function renderQueueEditBar() {
  let bar = document.getElementById('queue-edit');
  if (!state.queueEdit) {
    if (bar) {
      bar.hidden = true;
    }
    return;
  }
  if (!bar) {
    bar = document.createElement('div');
    bar.id = 'queue-edit';
    bar.className = 'queue-edit';
    const label = document.createElement('span');
    label.textContent = '正在编辑排队消息';
    const cancel = document.createElement('button');
    cancel.type = 'button';
    cancel.className = 'queue-edit-cancel';
    cancel.textContent = '取消';
    cancel.addEventListener('click', cancelQueueEdit);
    bar.append(label, cancel);
    el.messageQueue.insertAdjacentElement('afterend', bar);
  }
  bar.hidden = false;
}

function beginQueueEdit(index) {
  const text = state.queue[index];
  if (text == null) {
    return;
  }
  if (!state.queueEdit) {
    state.queueEdit = { index, previous: el.input.value };
  } else {
    state.queueEdit.index = index;
  }
  el.input.value = text;
  autoGrow();
  el.input.focus();
  syncComposer();
  renderQueue();
}

function cancelQueueEdit() {
  if (!state.queueEdit) {
    return;
  }
  el.input.value = state.queueEdit.previous || '';
  state.queueEdit = null;
  autoGrow();
  syncComposer();
  renderQueue();
}

async function saveQueuedEdit(content) {
  const edit = state.queueEdit;
  if (!edit) {
    return;
  }
  if (!content) {
    showNotice('排队的内容不能是空的，不想发就点删除。');
    return;
  }
  try {
    const data = await putQueueItem(edit.index, content);
    state.queue = Array.isArray(data.items) ? data.items : [];
    el.input.value = edit.previous || '';
    state.queueEdit = null;
    autoGrow();
    syncComposer();
    renderQueue();
  } catch (error) {
    showNotice('改队列失败：' + error.message);
  }
}

async function moveQueuedItem(index) {
  if (index <= 0 || !state.sessionId) {
    return;
  }
  const current = state.queue[index];
  const above = state.queue[index - 1];
  try {
    await putQueueItem(index - 1, current);
    const data = await putQueueItem(index, above);
    state.queue = Array.isArray(data.items) ? data.items : [];
    if (state.queueEdit) {
      if (state.queueEdit.index === index) {
        state.queueEdit.index = index - 1;
      } else if (state.queueEdit.index === index - 1) {
        state.queueEdit.index = index;
      }
    }
    renderQueue();
  } catch (error) {
    showNotice('调整顺序失败：' + error.message);
  }
}

async function putQueueItem(index, content) {
  const response = await apiFetch(
    `/api/sessions/${encodeURIComponent(state.sessionId)}/queue/${index}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content })
    });
  if (!response.ok) {
    throw new Error(await describeError(response));
  }
  return response.json();
}

function iconButton(label, svg, onClick) {
  const control = document.createElement('button');
  control.type = 'button';
  control.className = 'queue-icon';
  control.title = label;
  control.setAttribute('aria-label', label);
  control.innerHTML = svg;
  control.addEventListener('click', (event) => {
    event.stopPropagation();
    onClick();
  });
  return control;
}

function queueUpIcon() {
  return '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M8 12.5V4.2M4.8 7.2 8 3.8l3.2 3.4" stroke="currentColor" stroke-width="1.4" fill="none" stroke-linecap="round" stroke-linejoin="round"/></svg>';
}

function queueTrashIcon() {
  return '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M3.5 4.5h9M6.2 4.4V3.2h3.6v1.2M5.2 4.5l.4 8h4.8l.4-8" stroke="currentColor" stroke-width="1.3" fill="none" stroke-linecap="round" stroke-linejoin="round"/></svg>';
}

async function removeQueuedItem(index) {
  try {
    const response = await apiFetch(
      `/api/sessions/${encodeURIComponent(state.sessionId)}/queue/${index}`, { method: 'DELETE' });
    if (!response.ok) {
      throw new Error(await describeError(response));
    }
    const data = await response.json();
    state.queue = Array.isArray(data.items) ? data.items : [];
    renderQueue();
  } catch (error) {
    showNotice('移除失败：' + error.message);
  }
}

function setConnection(kind, label) {
  el.connection.className = 'conn is-' + kind;
  el.connectionText.textContent = label;
}

function setRunState(kind, label) {
  el.runState.className = 'run-state state-' + kind;
  el.runState.textContent = label;
}

function markSessionStatus(status) {
  const found = state.sessions.find((item) => item.id === state.sessionId);
  if (found && found.status !== status) {
    found.status = status;
    renderSessions();
  }
}

/** 运行计时：让「卡住」和「还在跑」在界面上可区分。 */
function startRunClock(startedAt) {
  state.runStartedAt = startedAt || Date.now();
  state.lastEventAt = Date.now();
  stopRunClock();
  state.clock = setInterval(tickRunClock, 1000);
  tickRunClock();
}

function stopRunClock() {
  if (state.clock) {
    clearInterval(state.clock);
    state.clock = null;
  }
  state.runStartedAt = null;
}

function tickRunClock() {
  if (!state.running) {
    stopRunClock();
    return;
  }
  if (state.modelRetryUntil > Date.now()) {
    const left = Math.max(1, Math.ceil((state.modelRetryUntil - Date.now()) / 1000));
    setRunState('warn', `等待重试 · ${left}s`);
    return;
  }
  state.modelRetryUntil = 0;
  const elapsed = elapsedText(Date.now() - (state.runStartedAt || Date.now()));
  const idle = Math.max(0, Math.round((Date.now() - (state.lastEventAt || Date.now())) / 1000));
  if (idle >= STALL_SECONDS) {
    setRunState('warn', `运行中 · ${elapsed} · ${idle}s 无新事件`);
  } else {
    setRunState('running', `运行中 · ${elapsed}`);
  }
}

function elapsedText(ms) {
  const total = Math.max(0, Math.round(ms / 1000));
  if (total < 60) {
    return total + 's';
  }
  const minutes = Math.floor(total / 60);
  if (minutes < 60) {
    return `${minutes}m${String(total % 60).padStart(2, '0')}s`;
  }
  return `${Math.floor(minutes / 60)}h${String(minutes % 60).padStart(2, '0')}m`;
}

function showNotice(message) {
  el.noticeText.textContent = message;
  el.notice.hidden = false;
}

function hideNotice() {
  el.notice.hidden = true;
}

function resetView() {
  clearThinking();
  stopRunClock();
  state.cursor = 0;
  state.toolRows.clear();
  state.pendingApprovals.clear();
  state.reply = null;
  state.replyText = '';
  state.replyLive = false;
  state.replyCommitted = '';
  state.turnShell = null;
  state.running = false;
  setRunState('idle', '空闲');
  state.runId = null;
  state.lastRunId = null;
  state.changedFiles = [];
  state.turnRuns = [];
  state.streamCard = null;
  state.streamText = '';
  syncComposer();
  el.messages.replaceChildren(el.emptyState);
  el.emptyState.hidden = false;
  el.messages.classList.add('is-empty');
  el.timeline.replaceChildren();
  el.timelineEmpty.hidden = false;
  state.activityCount = 0;
  el.toggleActivity.title = '活动面板 (Ctrl B)';
  renderApprovalDock();
  resetChanges();
}

function hideEmptyState() {
  if (!el.messages.classList.contains('is-empty')) {
    return;
  }
  el.messages.classList.remove('is-empty');
  el.emptyState.hidden = true;
}

function scrollThread() {
  el.messages.scrollTop = el.messages.scrollHeight;
}

function isNearBottom(node, slack = 140) {
  return node.scrollHeight - node.scrollTop - node.clientHeight < slack;
}

/* ---------- 工作台：文件树 / 预览 / 分栏 / @ 引用 ---------- */

function initWorkbench() {
  setupGutter(el.gutterLeft, '--sidebar-w', 210, 460, false);
  setupGutter(el.gutterRight, '--activity-w', 300, 720, true);
  el.input.addEventListener('input', onComposerInput);
  document.addEventListener('click', (event) => {
    if (!event.target.closest('.composer-wrap')) {
      hideRefMenu();
    }
  });
}

/** 拖动分栏：左栏按鼠标 X 定位，右栏按到右边缘的距离定位。 */
function setupGutter(node, variable, min, max, fromRight) {
  if (!node) {
    return;
  }
  node.addEventListener('pointerdown', (event) => {
    event.preventDefault();
    node.setPointerCapture(event.pointerId);
    node.classList.add('is-dragging');
    document.body.style.cursor = 'col-resize';
    const move = (moveEvent) => {
      const raw = fromRight ? window.innerWidth - moveEvent.clientX : moveEvent.clientX;
      const value = Math.max(min, Math.min(max, raw));
      document.documentElement.style.setProperty(variable, value + 'px');
    };
    const stop = () => {
      node.classList.remove('is-dragging');
      document.body.style.cursor = '';
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', stop);
    };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', stop);
  });
}

async function loadTree(path = '', container = el.tree) {
  if (!state.sessionId) {
    return;
  }
  const isRoot = container === el.tree;
  if (isRoot) {
    el.treeEmpty.hidden = true;
  }
  container.replaceChildren(treeHint('读取中…'));
  try {
    const data = await fetchTree(path);
    const entries = Array.isArray(data.entries) ? data.entries : [];
    container.replaceChildren();
    renderTreeEntries(entries, container);
    if (!entries.length) {
      container.append(treeHint(path ? '空文件夹' : '工作区是空的。'));
    } else if (data.truncated) {
      container.append(treeHint('目录内容过多，只显示了一部分。'));
    }
    // 懒加载展开出来的子项也要补上改动小黄点
    refreshChangeMarkers(changeMarkerPaths());
  } catch (error) {
    container.replaceChildren(treeHint('读取失败：' + error.message));
  }
}

async function fetchTree(path) {
  const response = await apiFetch(`/api/sessions/${encodeURIComponent(state.sessionId)}/files?path=${encodeURIComponent(path)}`);
  if (!response.ok) {
    throw new Error(await describeError(response));
  }
  return response.json();
}

function treeHint(text) {
  const node = document.createElement('p');
  node.className = 'tree-hint';
  node.textContent = text;
  return node;
}

/* 文件树图标：统一 16 格视图，颜色交给各自的状态类 */
const TREE_SVG = {
  chevron: '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M6.2 3.8 10.4 8l-4.2 4.2" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>',
  folder: '<svg class="icon-closed" viewBox="0 0 16 16" aria-hidden="true"><path d="M2.3 4.1h3.9l1.2 1.5h6.3v6.7c0 .7-.6 1.3-1.3 1.3H3.6c-.7 0-1.3-.6-1.3-1.3V4.1Z" fill="none" stroke="currentColor" stroke-width="1.25" stroke-linejoin="round"/></svg>'
    + '<svg class="icon-open" viewBox="0 0 16 16" aria-hidden="true"><path d="M2.3 12.6V4.1h3.9l1.2 1.5h5.4c.7 0 1.3.6 1.3 1.3v.7" fill="none" stroke="currentColor" stroke-width="1.25" stroke-linejoin="round"/><path d="M2.3 12.6l1.6-5c.1-.4.5-.7.9-.7h8.2c.6 0 1 .6.9 1.2l-1.3 4.5H2.3Z" fill="none" stroke="currentColor" stroke-width="1.25" stroke-linejoin="round"/></svg>',
  md: '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M3.9 2.6h5.3l3.1 3.1v7.7H3.9z" fill="none" stroke="currentColor" stroke-width="1.25" stroke-linejoin="round"/><path d="M8.9 2.7v3.1h3.1" fill="none" stroke="currentColor" stroke-width="1.2" stroke-linejoin="round"/><path d="M5.7 12V9.3l1.2 1.5 1.2-1.5V12" fill="none" stroke="currentColor" stroke-width="1.05" stroke-linecap="round" stroke-linejoin="round"/></svg>',
  log: '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M3.9 2.6h5.3l3.1 3.1v7.7H3.9z" fill="none" stroke="currentColor" stroke-width="1.25" stroke-linejoin="round"/><path d="M8.9 2.7v3.1h3.1" fill="none" stroke="currentColor" stroke-width="1.2" stroke-linejoin="round"/><path d="M5.9 8.4h4.2M5.9 10.4h4.2M5.9 12.4h2.4" fill="none" stroke="currentColor" stroke-width="1.1" stroke-linecap="round"/></svg>',
  sql: '<svg viewBox="0 0 16 16" aria-hidden="true"><ellipse cx="8" cy="4.5" rx="4.1" ry="1.7" fill="none" stroke="currentColor" stroke-width="1.25"/><path d="M3.9 4.5v7c0 .95 1.84 1.7 4.1 1.7s4.1-.75 4.1-1.7v-7" fill="none" stroke="currentColor" stroke-width="1.25"/></svg>',
  image: '<svg viewBox="0 0 16 16" aria-hidden="true"><rect x="2.8" y="3.6" width="10.4" height="8.8" rx="1.4" fill="none" stroke="currentColor" stroke-width="1.25"/><circle cx="6.2" cy="6.6" r="0.9" fill="currentColor"/><path d="M3.6 11.4 6.6 8.6l2.1 1.9 1.8-1.6 1.9 1.9" fill="none" stroke="currentColor" stroke-width="1.2" stroke-linejoin="round"/></svg>',
  xml: '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M5.9 4.6 2.9 8l3 3.4M10.1 4.6 13.1 8l-3 3.4" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/></svg>',
  shell: '<svg viewBox="0 0 16 16" aria-hidden="true"><rect x="2.8" y="3.6" width="10.4" height="8.8" rx="1.5" fill="none" stroke="currentColor" stroke-width="1.25"/><path d="M5.3 6.6 6.9 8l-1.6 1.4M8.4 9.9h2.3" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/></svg>',
  java: '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M4.3 6.3h5.9v3.1a2.95 2.95 0 0 1-2.95 2.9A2.95 2.95 0 0 1 4.3 9.4z" fill="none" stroke="currentColor" stroke-width="1.25" stroke-linejoin="round"/><path d="M10.2 7.3h1.1a1.5 1.5 0 0 1 0 3h-1.1" fill="none" stroke="currentColor" stroke-width="1.2"/><path d="M6 2.9c.5.6.5 1.1 0 1.7M8.2 2.9c.5.6.5 1.1 0 1.7" fill="none" stroke="currentColor" stroke-width="1.05" stroke-linecap="round"/></svg>',
  code: '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M6.5 3.4h-.6A1.5 1.5 0 0 0 4.4 4.9v1.3c0 .9-.5 1.6-1.2 1.8.7.2 1.2.9 1.2 1.8V11a1.5 1.5 0 0 0 1.5 1.6h.6M9.5 3.4h.6A1.5 1.5 0 0 1 11.6 5v1.3c0 .9.5 1.6 1.2 1.8-.7.2-1.2.9-1.2 1.8V11a1.5 1.5 0 0 1-1.5 1.6h-.6" fill="none" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round"/></svg>',
  config: '<svg viewBox="0 0 16 16" aria-hidden="true"><rect x="3.6" y="2.8" width="8.8" height="10.4" rx="1.3" fill="none" stroke="currentColor" stroke-width="1.25"/><path d="M5.8 6.2h4.4M5.8 8.2h4.4M5.8 10.2h2.6" fill="none" stroke="currentColor" stroke-width="1.1" stroke-linecap="round"/></svg>',
  style: '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M5.4 3.6 4.3 12.4M9.6 3.6 8.5 12.4M3.2 6.4h9.4M2.8 9.6h9.4" fill="none" stroke="currentColor" stroke-width="1.2" stroke-linecap="round"/></svg>',
  text: '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M3.6 4.2h8.8M3.6 8h8.8M3.6 11.8h5.4" fill="none" stroke="currentColor" stroke-width="1.25" stroke-linecap="round"/></svg>',
  file: '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M4.2 2.6h5.1l3 3v7.8H4.2z" fill="none" stroke="currentColor" stroke-width="1.25" stroke-linejoin="round"/><path d="M9.2 2.7v3h2.9" fill="none" stroke="currentColor" stroke-width="1.2" stroke-linejoin="round"/></svg>'
};

const TREE_EXT_GROUPS = [
  { cls: 'is-md', icon: 'md', exts: ['md', 'mdx', 'markdown'] },
  { cls: 'is-sql', icon: 'sql', exts: ['sql', 'sqlite', 'db'] },
  { cls: 'is-log', icon: 'log', exts: ['log', 'out'] },
  { cls: 'is-image', icon: 'image', exts: ['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'ico', 'bmp'] },
  { cls: 'is-xml', icon: 'xml', exts: ['xml', 'xsd', 'xsl', 'xslt', 'plist'] },
  { cls: 'is-shell', icon: 'shell', exts: ['sh', 'bash', 'zsh', 'fish', 'bat', 'cmd', 'ps1'] },
  { cls: 'is-java', icon: 'java', exts: ['java'] },
  { cls: 'is-code', icon: 'code', exts: ['js', 'mjs', 'cjs', 'ts', 'tsx', 'jsx', 'py', 'go', 'rs', 'kt', 'kts', 'c', 'h', 'cpp', 'hpp', 'cs', 'rb', 'php', 'swift', 'lua'] },
  { cls: 'is-config', icon: 'config', exts: ['json', 'yml', 'yaml', 'toml', 'ini', 'conf', 'properties'] },
  { cls: 'is-style', icon: 'style', exts: ['css', 'scss', 'less', 'sass'] },
  { cls: 'is-text', icon: 'text', exts: ['txt', 'rst', 'csv', 'tsv'] }
];

/** 目录优先、同类按名称排；后端已排过一遍，这里保证前端行为稳定。 */
function sortTreeEntries(entries) {
  return entries.slice().sort((a, b) => {
    if (Boolean(a.directory) !== Boolean(b.directory)) {
      return a.directory ? -1 : 1;
    }
    return String(a.name).localeCompare(String(b.name), 'zh-Hans-CN', { numeric: true, sensitivity: 'base' });
  });
}

function treeIconNode(markup, extraClass) {
  const span = document.createElement('span');
  span.className = 'tree-icon' + (extraClass ? ' ' + extraClass : '');
  span.innerHTML = markup;
  return span;
}

function treeFileIconNode(name) {
  const dot = name.lastIndexOf('.');
  const ext = dot > 0 ? name.slice(dot + 1).toLowerCase() : '';
  const group = TREE_EXT_GROUPS.find((item) => item.exts.includes(ext));
  return treeIconNode(TREE_SVG[group ? group.icon : 'file'], group ? group.cls : 'is-file');
}

function treeRow(entry) {
  const directory = Boolean(entry.directory);
  const row = document.createElement('button');
  row.type = 'button';
  row.className = 'tree-row' + (directory ? ' is-dir' : '');
  row.dataset.path = normalizePath(entry.path);
  row.dataset.dir = String(directory);
  row.title = entry.path;

  const caret = document.createElement('span');
  caret.className = 'tree-caret';
  if (directory) {
    caret.innerHTML = TREE_SVG.chevron;
  }

  const name = document.createElement('span');
  name.className = 'tree-name';
  name.textContent = entry.name;

  row.append(caret, directory ? treeIconNode(TREE_SVG.folder, 'is-dir') : treeFileIconNode(entry.name), name);
  return row;
}

function treeChildrenContainer(row, entry) {
  const children = document.createElement('div');
  children.className = 'tree-children';
  children.hidden = true;
  row.setAttribute('aria-expanded', 'false');
  row.addEventListener('click', () => toggleTreeFolder(row, children, entry.path));
  return children;
}

/** 展开即拉取子项；已加载过就直接切显隐，避免重复请求。 */
function toggleTreeFolder(row, children, path) {
  const open = !row.classList.contains('is-open');
  row.classList.toggle('is-open', open);
  row.setAttribute('aria-expanded', String(open));
  children.hidden = !open;
  if (!open || children.dataset.loaded === '1') {
    return;
  }
  children.dataset.loaded = '1';
  loadTree(path, children);
}

function renderTreeEntries(entries, container) {
  for (const entry of sortTreeEntries(entries)) {
    const row = treeRow(entry);
    container.append(row);
    if (entry.directory) {
      container.append(treeChildrenContainer(row, entry));
    } else {
      row.addEventListener('click', () => openPreview(entry.path, row));
    }
  }
}

async function openPreview(path, row) {
  if (!state.sessionId) {
    return;
  }
  state.previewPath = path;
  selectTab('preview');
  el.previewPath.textContent = path;
  el.previewPath.title = path;
  el.previewBody.textContent = '读取中…';
  highlightTree(path, row);
  try {
    const response = await apiFetch(
      `/api/sessions/${encodeURIComponent(state.sessionId)}/file?path=${encodeURIComponent(path)}`);
    if (!response.ok) {
      throw new Error(await describeError(response));
    }
    const data = await response.json();
    if (data.binary) {
      el.previewBody.textContent = '（二进制文件，无法预览）';
    } else if (data.truncated) {
      el.previewBody.textContent = '（文件超过 512 KB，未加载预览）';
    } else {
      el.previewBody.textContent = data.content && data.content.length ? data.content : '（空文件）';
    }
  } catch (error) {
    el.previewBody.textContent = '读取失败：' + error.message;
  }
}

function highlightTree(path, row) {
  const target = normalizePath(path);
  for (const item of el.tree.querySelectorAll('.tree-row.is-active')) {
    item.classList.remove('is-active');
  }
  const found = row || [...el.tree.querySelectorAll('.tree-row')]
    .find((item) => item.dataset.path === target && item.dataset.dir !== 'true');
  if (found) {
    found.classList.add('is-active');
  }
}

/** 改动点来源：优先用变更面板的完整 diff 清单，其次才是本轮运行改过的文件。 */
function changeMarkerPaths() {
  const report = state.changeReport;
  if (report && Array.isArray(report.files) && report.files.length) {
    return report.files;
  }
  return state.changedFiles || [];
}

/** 变更面板加载后，在文件树里给改动过的文件打点。 */
function refreshChangeMarkers(files) {
  const changed = new Set((files || []).map((file) => normalizePath(typeof file === 'string' ? file : file.path)));
  for (const row of el.tree.querySelectorAll('.tree-row')) {
    row.classList.toggle('is-changed', changed.has(row.dataset.path));
  }
}

/* ---------- 输入框：@ 引用与 / 快捷指令 ---------- */

function onComposerInput() {
  autoGrow();
  const caret = el.input.selectionStart;
  const before = el.input.value.slice(0, caret);
  // 路径里可能有中文、空格以外的任意字符；只按「空白之前」截断，@ 后不要求是 ASCII
  const fileMatch = /(^|\s)@([^\s@]*)$/.exec(before);
  const commandMatch = /^\/([\w-]*)(?:\s+.*)?$/.exec(before);
  if (fileMatch) {
    showRefMenu('file', fileMatch[2]);
  } else if (commandMatch) {
    showRefMenu('command', commandMatch[1]);
  } else {
    hideRefMenu();
  }
}

function showRefMenu(kind, query) {
  const items = kind === 'command'
    ? filterQuickCommands(query)
    : treeFiles().filter((path) => path.toLowerCase().includes(String(query).toLowerCase())).slice(0, 40);

  el.refMenu.replaceChildren();
  state.refItems = items;
  state.refKind = kind;
  state.refIndex = 0;
  if (!items.length) {
    hideRefMenu();
    return;
  }
  const title = document.createElement('p');
  title.className = 'ref-title';
  title.textContent = kind === 'command'
    ? '快捷指令'
    : (state.fileIndexLoading ? '引用文件（正在索引工作区…）' : '引用文件');
  el.refMenu.append(title);

  items.forEach((item, index) => {
    const row = document.createElement('button');
    row.type = 'button';
    row.className = 'ref-item' + (index === 0 ? ' is-on' : '');
    const label = document.createElement('span');
    const desc = document.createElement('span');
    desc.className = 'ref-desc';
    if (kind === 'command') {
      label.textContent = item.name;
      desc.textContent = `${item.label} · ${item.hint}`;
    } else {
      label.className = 'mono';
      label.textContent = item;
      desc.textContent = '文件';
    }
    row.append(label, desc);
    row.addEventListener('mousedown', (event) => {
      event.preventDefault();
      applyRef(item);
    });
    el.refMenu.append(row);
  });
  el.refMenu.hidden = false;
}

function hideRefMenu() {
  el.refMenu.hidden = true;
  state.refItems = [];
}

function treeFiles() {
  return [...el.tree.querySelectorAll('.tree-row')]
    .filter((row) => row.dataset.dir === 'false')
    .map((row) => row.dataset.path);
}

/** 有完整索引就用它，否则退回文件树里已加载的节点。 */
function mentionCandidates() {
  return state.fileIndex.length ? state.fileIndex : treeFiles();
}

/**
 * 拉一份会话工作区的完整文件清单。
 *
 * <p>文件树是点开目录才加载的，只用 DOM 会让 @ 只能选到根目录那几个文件。
 * 这里按会话缓存一次；失败后 10 秒内不重复请求，避免每敲一个字打一次接口。
 */
function ensureFileIndex() {
  const sessionId = state.sessionId;
  if (!sessionId) {
    return;
  }
  if (state.fileIndexSession !== sessionId) {
    state.fileIndex = [];
    state.fileIndexSession = sessionId;
    state.fileIndexFailedAt = 0;
  }
  if (state.fileIndex.length || state.fileIndexLoading) {
    return;
  }
  if (Date.now() - state.fileIndexFailedAt < 10_000) {
    return;
  }
  state.fileIndexLoading = true;
  apiFetch(`/api/sessions/${encodeURIComponent(sessionId)}/files/flat`)
    .then((response) => (response.ok ? response.json() : null))
    .then((data) => {
      if (!data || state.fileIndexSession !== sessionId) {
        return;
      }
      state.fileIndex = Array.isArray(data.files) ? data.files : [];
    })
    .catch(() => {
      state.fileIndexFailedAt = Date.now();
    })
    .finally(() => {
      state.fileIndexLoading = false;
      // 索引回来时菜单可能还开着，按当前输入重渲染
      if (!el.refMenu.hidden && state.refKind === 'file' && state.sessionId === sessionId) {
        onComposerInput();
      }
    });
}

function applyRef(item) {
  const value = el.input.value;
  const caret = el.input.selectionStart;
  const before = value.slice(0, caret);
  const after = value.slice(caret);
  const kind = state.refKind;
  if (kind === 'command') {
    const commandMatch = /^\/([\w-]*)(?:\s+(.*))?$/.exec(before);
    const prompt = expandQuickCommand(item, commandMatch?.[2] || '');
    el.input.value = prompt + after;
  } else {
    const replaced = before.replace(/@([\w./-]*)$/, '@' + item + ' ');
    el.input.value = replaced + after;
  }
  hideRefMenu();
  autoGrow();
  el.input.focus();
  el.input.setSelectionRange(el.input.value.length, el.input.value.length);
}

function onComposerKeydown(event) {
  if (!el.refMenu.hidden && state.refItems && state.refItems.length) {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      const delta = event.key === 'ArrowDown' ? 1 : -1;
      state.refIndex = (state.refIndex + delta + state.refItems.length) % state.refItems.length;
      const rows = [...el.refMenu.querySelectorAll('.ref-item')];
      rows.forEach((row, index) => {
        row.classList.toggle('is-on', index === state.refIndex);
      });
      // 菜单只有 250px 高，选中项滚出可视区就看不见了
      rows[state.refIndex]?.scrollIntoView({ block: 'nearest' });
      return true;
    }
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      applyRef(state.refItems[state.refIndex]);
      return true;
    }
    if (event.key === 'Escape') {
      event.preventDefault();
      hideRefMenu();
      return true;
    }
  }
  return false;
}

/* ---------- 工具函数 ---------- */

function button(label, className, onClick) {
  const node = document.createElement('button');
  node.type = 'button';
  node.className = className;
  node.textContent = label;
  node.addEventListener('click', onClick);
  return node;
}

const FENCE_PATTERN = /^\s{0,3}(`{3,}|~{3,})\s*([\w+#.-]*)\s*$/;
const HEADING_PATTERN = /^\s{0,3}(#{1,6})\s+(.*?)\s*#*\s*$/;
const RULE_PATTERN = /^\s{0,3}([-*_])(\s*\1){2,}\s*$/;
const QUOTE_PATTERN = /^\s{0,3}>\s?/;
const LIST_PATTERN = /^\s{0,3}([-*+]|\d+[.)])\s+(.*)$/;
const TABLE_DIVIDER = /^:?-{2,}:?$/;

/**
 * 把模型返回的 Markdown 渲染成 DOM。只构造节点、不拼 innerHTML，天然免疫 HTML 注入。
 * 支持：围栏代码块、标题、表格、引用、有序/无序列表、分隔线、粗体/斜体/删除线/行内代码/链接。
 * 流式期间未闭合的代码围栏会一直按代码渲染，避免逐字闪烁。
 */
function renderMarkdown(text) {
  const fragment = document.createDocumentFragment();
  for (const node of markdownBlocks(String(text).replace(/\r\n/g, '\n').split('\n'))) {
    fragment.append(node);
  }
  return fragment;
}

function markdownBlocks(lines) {
  const nodes = [];
  let i = 0;

  while (i < lines.length) {
    const line = lines[i];

    if (!line.trim()) {
      i++;
      continue;
    }

    const fence = line.match(FENCE_PATTERN);
    if (fence) {
      const marker = fence[1][0];
      const length = fence[1].length;
      const language = fence[2] || '';
      i++;
      const code = [];
      while (i < lines.length) {
        const closing = lines[i].match(/^\s{0,3}(`{3,}|~{3,})\s*$/);
        if (closing && closing[1][0] === marker && closing[1].length >= length) {
          i++;
          break;
        }
        code.push(lines[i]);
        i++;
      }
      nodes.push(codeBlock(code.join('\n'), language));
      continue;
    }

    if (RULE_PATTERN.test(line)) {
      nodes.push(document.createElement('hr'));
      i++;
      continue;
    }

    const heading = line.match(HEADING_PATTERN);
    if (heading) {
      const node = document.createElement('h' + heading[1].length);
      node.append(inlineFragment(heading[2]));
      nodes.push(node);
      i++;
      continue;
    }

    if (isTableStart(lines, i)) {
      const table = buildTable(lines, i);
      nodes.push(table.node);
      i = table.next;
      continue;
    }

    if (QUOTE_PATTERN.test(line)) {
      const quoted = [];
      while (i < lines.length && QUOTE_PATTERN.test(lines[i])) {
        quoted.push(lines[i].replace(QUOTE_PATTERN, ''));
        i++;
      }
      const blockquote = document.createElement('blockquote');
      for (const node of markdownBlocks(quoted)) {
        blockquote.append(node);
      }
      nodes.push(blockquote);
      continue;
    }

    if (LIST_PATTERN.test(line)) {
      const list = buildList(lines, i);
      nodes.push(list.node);
      i = list.next;
      continue;
    }

    const paragraph = [];
    while (i < lines.length) {
      const current = lines[i];
      if (!current.trim()) {
        break;
      }
      if (FENCE_PATTERN.test(current) || RULE_PATTERN.test(current)
          || HEADING_PATTERN.test(current) || QUOTE_PATTERN.test(current)
          || LIST_PATTERN.test(current) || isTableStart(lines, i)) {
        break;
      }
      paragraph.push(current.trim());
      i++;
    }
    if (!paragraph.length) {
      i++;
      continue;
    }
    const node = document.createElement('p');
    paragraph.forEach((part, index) => {
      if (index) {
        node.append(document.createElement('br'));
      }
      node.append(inlineFragment(part));
    });
    nodes.push(node);
  }

  return nodes;
}

function codeBlock(code, language) {
  const pre = document.createElement('pre');
  const el = document.createElement('code');
  if (language) {
    el.dataset.lang = language;
  }
  el.textContent = code.replace(/\n+$/, '');
  pre.append(el);
  return pre;
}

function splitRow(line) {
  let value = line.trim();
  if (value.startsWith('|')) {
    value = value.slice(1);
  }
  if (value.endsWith('|')) {
    value = value.slice(0, -1);
  }
  return value.split('|').map((cell) => cell.trim());
}

function isTableStart(lines, index) {
  const header = lines[index];
  if (!header || !header.includes('|')) {
    return false;
  }
  const divider = lines[index + 1];
  if (!divider || !divider.includes('-')) {
    return false;
  }
  const cells = splitRow(divider);
  return cells.length > 0 && cells.every((cell) => TABLE_DIVIDER.test(cell.replace(/\s/g, '')));
}

function buildTable(lines, start) {
  const header = splitRow(lines[start]);
  const aligns = splitRow(lines[start + 1]).map((cell) => {
    const value = cell.replace(/\s/g, '');
    if (value.startsWith(':') && value.endsWith(':')) {
      return 'center';
    }
    if (value.endsWith(':')) {
      return 'right';
    }
    return '';
  });

  const table = document.createElement('table');
  const thead = document.createElement('thead');
  const headRow = document.createElement('tr');
  header.forEach((cell, index) => {
    const th = document.createElement('th');
    if (aligns[index]) {
      th.style.textAlign = aligns[index];
    }
    th.append(inlineFragment(cell));
    headRow.append(th);
  });
  thead.append(headRow);
  table.append(thead);

  const tbody = document.createElement('tbody');
  let i = start + 2;
  while (i < lines.length && lines[i].trim() && lines[i].includes('|')) {
    const cells = splitRow(lines[i]);
    const row = document.createElement('tr');
    header.forEach((ignored, index) => {
      const td = document.createElement('td');
      if (aligns[index]) {
        td.style.textAlign = aligns[index];
      }
      td.append(inlineFragment(cells[index] || ''));
      row.append(td);
    });
    tbody.append(row);
    i++;
  }
  table.append(tbody);
  return { node: table, next: i };
}

function buildList(lines, start) {
  const ordered = /^\s{0,3}\d/.test(lines[start]);
  const list = document.createElement(ordered ? 'ol' : 'ul');
  let i = start;
  while (i < lines.length) {
    const item = lines[i].match(LIST_PATTERN);
    if (!item) {
      break;
    }
    if (/^\s{0,3}\d/.test(lines[i]) !== ordered) {
      break;
    }
    const li = document.createElement('li');
    li.append(inlineFragment(item[2]));
    list.append(li);
    i++;
  }
  return { node: list, next: i };
}

function inlineFragment(text) {
  const fragment = document.createDocumentFragment();
  const pattern = /(`[^`\n]+`|\*\*[^*\n]+\*\*|~~[^~\n]+~~|\*[^*\n]+\*|\[[^\]\n]+\]\([^)\s]+\))/g;
  let last = 0;
  let match;
  while ((match = pattern.exec(text)) !== null) {
    if (match.index > last) {
      fragment.append(document.createTextNode(text.slice(last, match.index)));
    }
    fragment.append(inlineToken(match[0]));
    last = match.index + match[0].length;
  }
  if (last < text.length) {
    fragment.append(document.createTextNode(text.slice(last)));
  }
  return fragment;
}

function inlineToken(token) {
  if (token.startsWith('`')) {
    const code = document.createElement('code');
    code.textContent = token.slice(1, -1);
    return code;
  }
  if (token.startsWith('**')) {
    const strong = document.createElement('strong');
    strong.append(inlineFragment(token.slice(2, -2)));
    return strong;
  }
  if (token.startsWith('~~')) {
    const del = document.createElement('del');
    del.textContent = token.slice(2, -2);
    return del;
  }
  if (token.startsWith('*')) {
    const em = document.createElement('em');
    em.textContent = token.slice(1, -1);
    return em;
  }
  const link = token.match(/^\[([^\]]+)\]\(([^)\s]+)\)$/);
  if (link && /^https?:\/\//i.test(link[2])) {
    const anchor = document.createElement('a');
    anchor.href = link[2];
    anchor.textContent = link[1];
    anchor.target = '_blank';
    anchor.rel = 'noreferrer noopener';
    return anchor;
  }
  return document.createTextNode(token);
}

function formatTime(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return '';
  }
  return date.toLocaleTimeString('zh-CN', { hour12: false });
}

function preview(value, maxLength) {
  const single = String(value || '').replace(/\s+/g, ' ').trim();
  return single.length <= maxLength ? single : single.slice(0, maxLength) + '…';
}
