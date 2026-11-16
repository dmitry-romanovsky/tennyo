// Loads a bundled extension the way VS Code does, with a stand-in `vscode` module, and fails unless it exports
// activate/deactivate at the top level, the shared core's self-check reports ready, and the status bar item carries
// the companion card as a trusted Markdown hover whose links run `tennyo.act`.
//
// It runs twice, in a fresh process each time. With no folder open the card still offers every mode, and
// "Pairing mode" asks for a folder. With a folder open it must reach a live session and switch
// Pairing mode -> Off -> Known devices through the card's links, raising no notification on the way.
//
// Usage: node activate-smoke.cjs <path to extension.js> [no-folder|with-folder]

const Module = require("module");
const path = require("path");
const os = require("os");
const fs = require("fs");
const { spawnSync } = require("child_process");

const scenario = process.argv[3] ?? "no-folder";
const withFolder = scenario === "with-folder";
const folder = withFolder ? fs.mkdtempSync(path.join(os.tmpdir(), "tennyo-smoke-")) : null;

const lines = [];
const channel = {};
for (const level of ["trace", "debug", "info", "warn", "error"]) {
    channel[level] = (message) => lines.push({ level, message });
}
channel.dispose = () => {};

const commands = new Map();
const statusBarItems = [];
const notices = [];
const settings = {};
const settingTargets = {};
const executed = [];
const picks = [];
const memento = new Map();
const workspaceMemento = new Map();

const vscode = {
    StatusBarAlignment: { Left: 1, Right: 2 },
    window: {
        createOutputChannel: (name, options) => {
            if (name !== "Tennyo Companion" || !options || options.log !== true) {
                fail(`unexpected output channel ${JSON.stringify(name)} ${JSON.stringify(options)}`);
            }
            return channel;
        },
        createStatusBarItem: (alignment, priority) => {
            const item = { alignment, priority, shown: false, show: () => { item.shown = true; }, dispose: () => {} };
            statusBarItems.push(item);
            return item;
        },
        createWebviewPanel: () => fail("the panel should be the status item's hover card, not an editor tab"),
        createQuickPick: () => {
            const listeners = { accept: [], hide: [] };
            const pick = {
                items: [], selectedItems: [], shown: false,
                onDidAccept: (listener) => { listeners.accept.push(listener); return { dispose() {} }; },
                onDidHide: (listener) => { listeners.hide.push(listener); return { dispose() {} }; },
                onDidTriggerItemButton: () => ({ dispose() {} }),
                show: () => { pick.shown = true; },
                hide: () => { pick.shown = false; listeners.hide.forEach((l) => l()); },
                dispose: () => {},
                /** Accepts the item whose label ends with [title], as a click on it would. */
                choose: (title) => {
                    const item = pick.items.find((candidate) => candidate.label.endsWith(title) && candidate.choice);
                    if (!item) fail(`the pick list has no ${title} item: ${JSON.stringify(pick.items.map((i) => i.label))}`);
                    pick.selectedItems = [item];
                    listeners.accept.forEach((l) => l());
                },
            };
            picks.push(pick);
            return pick;
        },
        showInformationMessage: (message, ...items) => notify("info", message, items),
        showWarningMessage: (message, ...items) => notify("warning", message, items),
    },
    version: "1.126.0",
    env: { appName: "Visual Studio Code" },
    workspace: {
        // With no folder open `workspaceFolders` is undefined, not an empty array, exactly as VS Code reports it.
        workspaceFolders: folder ? [{ uri: { fsPath: folder }, name: path.basename(folder) }] : undefined,
        textDocuments: [],
        applyEdit: () => thenable(true),
        openTextDocument: () => thenable({}),
        onDidChangeTextDocument: () => ({ dispose: () => {} }),
        onDidSaveTextDocument: () => ({ dispose: () => {} }),
        onDidChangeWorkspaceFolders: () => ({ dispose: () => {} }),
        createFileSystemWatcher: () => ({
            onDidChange: () => ({ dispose: () => {} }),
            onDidCreate: () => ({ dispose: () => {} }),
            onDidDelete: () => ({ dispose: () => {} }),
            dispose: () => {},
        }),
        getConfiguration: (section) => ({
            get: (key) => settings[`${section}.${key}`],
            update: (key, value, global) => {
                settings[`${section}.${key}`] = value;
                settingTargets[`${section}.${key}`] = global;
                return thenable(undefined);
            },
        }),
    },
    Uri: { file: (path) => ({ fsPath: path }) },
    ThemeColor: class {
        constructor(id) { this.id = id; }
    },
    ThemeIcon: class {
        constructor(id) { this.id = id; }
    },
    MarkdownString: class {
        constructor(value, supportThemeIcons) {
            this.value = value;
            this.supportThemeIcons = supportThemeIcons;
        }
    },
    Range: class {},
    WorkspaceEdit: class {
        replace() {}
    },
    commands: {
        registerCommand: (command, callback) => {
            commands.set(command, callback);
            return { dispose: () => commands.delete(command) };
        },
        executeCommand: (command) => {
            executed.push(command);
            return thenable(undefined);
        },
    },
};

vscode.window.tabGroups = {
    all: [],
    activeTabGroup: { isActive: true, activeTab: undefined, tabs: [] },
    close: () => thenable(true),
    onDidChangeTabs: () => ({ dispose: () => {} }),
};
vscode.window.showTextDocument = () => thenable({});
vscode.window.onDidChangeActiveTextEditor = () => ({ dispose: () => {} });

/** One shown notification; `click` resolves it with the button of that title, as the editor does. */
function notify(level, message, items) {
    let resolve = () => {};
    const promise = { then: (onFulfilled) => { resolve = onFulfilled; return promise; } };
    notices.push({
        level,
        message,
        titles: items.map((item) => item.title),
        click: (title) => {
            const item = items.find((candidate) => candidate.title === title);
            if (!item) fail(`the notification ${JSON.stringify(message)} has no ${title} button`);
            resolve(item);
        },
    });
    return promise;
}

function thenable(value) {
    return { then: (onFulfilled) => { onFulfilled(value); return thenable(value); } };
}

const load = Module._load;
Module._load = function (request, parent, isMain) {
    return request === "vscode" ? vscode : load.call(this, request, parent, isMain);
};

function fail(reason) {
    console.error(`Tennyo Companion activation smoke test failed: ${reason}`);
    if (lines.length > 0) console.error(JSON.stringify(lines, null, 2));
    process.exit(1);
}

// A Kotlin `?.let` on a `dynamic` value compiles to a JavaScript method call that throws at run time.
if (/safe_receiver\.let\(/.test(fs.readFileSync(path.resolve(process.argv[2]), "utf8"))) {
    fail("the bundle calls .let on a dynamic value");
}

const extension = require(path.resolve(process.argv[2]));
if (typeof extension.activate !== "function") fail("activate is not exported at the top level");
if (typeof extension.deactivate !== "function") fail("deactivate is not exported at the top level");

// An agent-host endpoint nobody listens on: attaching loads the bundled protocol client and fails to connect.
const userData = fs.mkdtempSync(path.join(os.tmpdir(), "tennyo-smoke-user-"));
const endpoints = path.join(userData, "agent-host", "local-endpoint", "entries");
fs.mkdirSync(endpoints, { recursive: true });
fs.writeFileSync(path.join(endpoints, "smoke.json"), JSON.stringify({
    type: "editor", pid: process.pid, connectionToken: "t", endpoint: { type: "tcp", host: "127.0.0.1", port: 9 },
}));

const context = {
    subscriptions: [],
    globalStorageUri: { fsPath: path.join(userData, "User", "globalStorage", "tennyo.tennyo-host") },
    extension: { id: "tennyo.tennyo-host" },
    globalState: {
        get: (key) => memento.get(key),
        update: (key, value) => { memento.set(key, value); return thenable(undefined); },
    },
    workspaceState: {
        get: (key) => workspaceMemento.get(key),
        update: (key, value) => { workspaceMemento.set(key, value); return thenable(undefined); },
    },
};
extension.activate(context);

// VS Code's Agents window runs Tennyo only once the user's setting lets it in: the first start offers that, once.
const offer = notices.shift();
if (!offer || !offer.message.includes("Agents window")) fail("the extension did not offer the Agents window");
offer.click("Show in Agents window");
if (settings["extensions.supportAgentsWindow"]?.["tennyo.tennyo-host"] !== true || !settingTargets["extensions.supportAgentsWindow"]) {
    fail("accepting the offer did not let Tennyo into the Agents window in the user's settings");
}
notices.length = 0;

const [statusBarItem, ...extraItems] = statusBarItems;
if (!statusBarItem) fail("the extension did not create a status bar item");
if (extraItems.length > 0) fail(`the extension created ${statusBarItems.length} status bar items`);
if (statusBarItem.alignment !== vscode.StatusBarAlignment.Right) fail("the status bar item is not right-aligned");
if (!statusBarItem.shown) fail("the status bar item is never shown");
if (!statusBarItem.text) fail("the status bar item has no label");
if (!context.subscriptions.includes(statusBarItem)) fail("the status bar item is not disposed with the extension");
if (statusBarItem.command !== "tennyo.showCompanionPanel") fail("clicking the status bar item does not open the card");

/** The card's Markdown as the hover would render it. */
const card = () => {
    const tooltip = statusBarItem.tooltip;
    if (!tooltip || typeof tooltip.value !== "string") fail("the status bar item has no Markdown card");
    if (!tooltip.isTrusted?.enabledCommands?.includes("tennyo.act")) fail("the card's links are not trusted");
    return tooltip.value;
};

/** Clicks the card link titled [title] (after its codicon), the way the hover runs a `command:` link. */
function clickLink(title) {
    const match = new RegExp(String.raw`\[\$\([a-z-]+\) ${title}\]\(command:tennyo\.act\?`).exec(card());
    if (!match) fail(`the card has no ${title} link: ${JSON.stringify(card())}`);
    const start = match.index;
    const prefix = match[0];
    const encoded = card().slice(start + prefix.length, card().indexOf(")", start + prefix.length));
    const [argument] = JSON.parse(decodeURIComponent(encoded));
    const act = commands.get("tennyo.act");
    if (!act) fail("tennyo.act is not registered");
    act(argument);
}

if (withFolder) {
    verifyHostingWindow();
} else {
    verifyWindowWithoutFolder();
}

/** With no folder open every mode is still offered; "Pairing mode" asks for a folder and pairs once it opens. */
function verifyWindowWithoutFolder() {
    if (!card().includes("Open a folder")) fail(`the card should ask for a folder; it says ${JSON.stringify(card())}`);
    // A click opens the same card as a pick list, which acts on the session and redraws with it.
    commands.get(statusBarItem.command)();
    const pick = picks[0];
    if (!pick?.shown || !pick.title.includes("Known devices")) fail(`the click did not open the card list: ${pick?.title}`);
    pick.choose("Turn off");
    if (pick.shown) fail("choosing an action did not close the list");
    commands.get(statusBarItem.command)();
    if (!picks[1]?.title.includes("Off")) fail(`the list did not follow the mode: ${picks[1]?.title}`);
    picks[1].hide();
    clickLink("Allow only known devices");
    clickLink("Turn off");
    waitFor(() => card().includes("**Tennyo** | Off |"), 3_000, (off) => {
        if (!off) fail(`choosing Off did not switch the mode; the card says ${JSON.stringify(card())}`);
        clickLink("Open a folder to pair");
        if (!executed.includes("vscode.openFolder")) fail("Pairing mode without a folder did not open the folder picker");
        if (memento.get("tennyo.pairWhenFolderOpens") !== true) fail("the window opened with the folder will not pair");
        finish();
    });
}

/**
 * With a folder open the companion binds its listener and switches modes through the card. Binding is asynchronous; a
 * machine with no private network address cannot listen at all, which the run reports rather than pretending it passed.
 */
function verifyHostingWindow() {
    waitFor(() => card().includes("**Tennyo** | Known devices |"), 10_000, (reached) => {
        if (!reached) {
            if (lines.some((line) => line.message.includes("could not bind"))) {
                console.log("Tennyo Companion smoke test: this machine cannot listen, so hosting was not checked");
                finish();
                return;
            }
            fail(`the window never started hosting; the card says ${JSON.stringify(card())}`);
        }
        step("Open pairing mode", "**Tennyo** | Pairing mode |", () => holdsStill(() =>
            step("Turn off", "**Tennyo** | Off |", () =>
                step("Allow only known devices", "**Tennyo** | Known devices |", verifyRemoteAccess))));
    });
}

/** Remote access switches on from the card and off again; where it listens depends on the machine's networks. */
function verifyRemoteAccess() {
    if (typeof workspaceMemento.get("tennyo.listenerPort") !== "number") fail("the window did not remember its port");
    step("Turn on remote access", "Turn off remote access", () =>
        step("Turn off remote access", "Turn on remote access", finish));
}

/** The card counts down in pairing mode, yet must not be reassigned while its text stays the same: that flickers. */
function holdsStill(next) {
    const shown = statusBarItem.tooltip;
    setTimeout(() => {
        if (statusBarItem.tooltip !== shown) fail("the card was redrawn with nothing changed on it; an open hover flickers");
        next();
    }, 2_500);
}

/** Clicks the card link [title] and waits for the card to say [expected]. */
function step(title, expected, next) {
    clickLink(title);
    waitFor(() => card().includes(expected), 3_000, (reached) => {
        if (!reached) fail(`clicking ${title} did not lead to "${expected}"; the card says ${JSON.stringify(card())}`);
        next();
    });
}

/** Polls [condition] until it holds or [timeoutMillis] passes, then hands the answer to [done]. */
function waitFor(condition, timeoutMillis, done) {
    const deadline = Date.now() + timeoutMillis;
    const tick = () => {
        if (condition()) return done(true);
        if (Date.now() > deadline) return done(false);
        setTimeout(tick, 100);
    };
    tick();
}

/** Ends the run once the agent-host attempt, which runs in the background, has reported. */
function finish() {
    const attached = () => lines.some((line) => line.message.includes("VS Code's agent host did not answer"));
    waitFor(attached, 5_000, () => verifyAndExit());
}

function verifyAndExit() {
    if (notices.length > 0) fail(`modes should not raise notifications: ${JSON.stringify(notices.map((n) => n.message))}`);
    extension.deactivate();
    if (!context.subscriptions.includes(channel)) fail("the output channel is not disposed with the extension");
    if (lines.some((line) => line.level === "error")) fail(`the extension logged an error: ${JSON.stringify(lines)}`);
    if (!lines.some((line) => line.level === "info" && line.message === "Tennyo Companion core ready")) {
        fail("the core self-check did not report ready");
    }
    const attach = lines.find((line) => line.message.includes("VS Code's agent host did not answer"));
    if (!attach?.message.includes("ECONNREFUSED")) fail(`the agent host client did not load: ${JSON.stringify(attach)}`);
    if (folder) fs.rmSync(folder, { recursive: true, force: true });
    fs.rmSync(userData, { recursive: true, force: true });
    if (!withFolder) {
        // The second scenario needs an unloaded module registry, so it runs in its own process.
        const next = spawnSync(process.execPath, [__filename, process.argv[2], "with-folder"], { stdio: "inherit" });
        if (next.status !== 0) process.exit(next.status ?? 1);
    }
    console.log(`Tennyo Companion activation smoke test passed (${scenario})`);
    // The DNS-SD socket may still be closing; the run is over either way.
    process.exit(0);
}
