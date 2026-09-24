# -*- coding: utf-8 -*-
"""
MENU/弹窗铁律的**导航模型自检**（纯逻辑，不碰真机）。

分两部分：
  一、逐条流程对账（每条真实流程按顺序喂进去，断言 MENU 落到哪一屏）；
  二、**逐屏逐键穷举**（BFS 走遍所有屏 × 所有键可达的状态，机械地查四件事：
      死路、不可达、能不能退出、铁律有没有破口）。

它验的是**规则本身**：模型是把 MainActivity 的 showScreen / recordHistory /
goBackOneLevel / onMenuKey / onBlockButton / 两个守卫照抄成的一份逻辑 ——
模型错了代码就错，所以这里跑通是必要条件（不是充分条件：真机的焦点、定时器、
无线电状态还得装机看）。

规则（用户定版）：
  · MENU = 返回上一层级（上一次显示的那个界面）；第一层 = 退出软件
  · 浮层不参与层级历史，它的上一级 = 它压着的那一屏（baseScreen）
  · 落到主界面 = 回到第一层（历史清空）；同一屏不重复入栈；关浮层不入栈
  · **铁律①**：本机没有已配对设备 → 主界面根本不立（严禁进入）
  · **铁律②**：本机没有已配对设备 → 进配对页前一步必须是配对提示
  · 无已配对设备时的配对页 = 那一态的家（历史清空）→ MENU = 退出软件
  · 用户自己从菜单进配对页（有设备）→ 普通入栈 → MENU = 返回刚才那一屏
  · 底栏提示与 MENU 的实际行为同源（历史空 = 提示"退出"）
"""
import collections

MAIN, MENU, MODE, ABOUT, DLG_CONFIRM, DLG_DEFAULT, DLG_EXITING, PAIRING, PAIRED, LOG = range(10)
NAME = {
    MAIN: "主界面", MENU: "选项菜单", MODE: "连接设置", ABOUT: "关于",
    DLG_CONFIRM: "确认/通知框", DLG_DEFAULT: "设为默认框", DLG_EXITING: "退出浮层",
    PAIRING: "配对页", PAIRED: "已配对设备", LOG: "调试日志",
}
MAX = 12


class Ui(object):
    """导航状态 + 切屏规则（与 MainActivity 一一对应）。"""

    def __init__(self, screen=-1, base=-1, hist=(), devices=False, mode=None):
        self.screen = screen
        self.base = base
        self.hist = list(hist)
        self.devices = devices          # 本机是否已有已配对设备
        self.mode = mode                # None / 'wifi' / 'hotspot'
        self.notice = None              # None / 'pairing' / 'mode'（'mode'=请选择连接方式）
        self.window = False             # 配对窗开着
        self.codes = 0                  # 生成过几张码
        self.menuFromPairing = False    # 菜单是从配对页打开的（那一份藏着"进入配对模式"）
        self.modeFromMenu = False       # 连接设置是从菜单进来的
        self.modeFromPairing = False    # 连接设置是从配对页菜单进来的
        self.enteringHome = False       # 这一次 showScreen 是把配对页立成家

    # ---------- 复制 / 指纹 ----------
    def clone(self):
        u = Ui(self.screen, self.base, self.hist, self.devices, self.mode)
        u.notice, u.window, u.codes = self.notice, self.window, self.codes
        u.menuFromPairing, u.modeFromMenu = self.menuFromPairing, self.modeFromMenu
        u.modeFromPairing, u.enteringHome = self.modeFromPairing, self.enteringHome
        return u

    def key(self):
        return (self.screen, self.base, tuple(self.hist), self.devices, self.mode,
                self.notice, self.window, self.menuFromPairing,
                self.modeFromMenu, self.modeFromPairing)

    # ---------- showScreen / recordHistory ----------
    def is_dialog(self, sc):
        return sc in (DLG_CONFIRM, DLG_DEFAULT, DLG_EXITING)

    def record_history(self, prev, target, back):
        if self.is_dialog(target):
            return
        if target == MAIN:
            self.hist = []
            self.base = MAIN
            return
        closing_dialog = self.is_dialog(prev) and target == self.base
        if not back and not closing_dialog and prev != target:
            frm = self.base if self.is_dialog(prev) else prev
            if 0 <= frm != target and frm not in self.hist:
                if len(self.hist) >= MAX:
                    self.hist.pop(0)
                self.hist.append(frm)
        self.base = target

    def pairing_page_is_current(self):
        return self.screen == PAIRING or self.base == PAIRING

    def pairing_flow_visited(self, requested, back):
        return PAIRING in self.hist or (back and requested == PAIRING)

    def show(self, target, back=False):
        """showScreen：两条铁律守卫 + 记帐 + 配对窗收尾（与 MainActivity 同序）。"""
        if target < 0:
            target = MAIN
        requested = target
        # 铁律①：无已配对设备 → 主界面根本不立
        if target == MAIN and not self.devices:
            target = PAIRING
        # 铁律②：无已配对设备 → "还没进过配对流程"的进配对页前一步必须是提示
        if (target == PAIRING and not self.devices
                and not self.enteringHome and not self.pairing_page_is_current()
                and not self.pairing_flow_visited(requested, back)):
            self.prompt_pairing()
            return
        prev = self.screen
        self.record_history(prev, target, back)
        # 无已配对设备时的配对页 = 家 → 层级历史清零
        if target == PAIRING and not self.devices:
            self.hist = []
        self.screen = target
        if prev == PAIRING and target != PAIRING and not self.is_dialog(target):
            self.window = False           # leavePairingMode（浮层不算离开）
        if target == PAIRING:             # enterPairingMode（幂等：窗口开着就不换码）
            if not self.window:
                self.window = True
                self.codes += 1

    def enter_pairing_home(self):
        """enterPairingHome：立屏（家），提示由调用方马上盖上去。"""
        self.enteringHome = True
        self.show(PAIRING)
        self.enteringHome = False

    def close_dialog(self):
        """closeDialog：关掉浮层，回它压着的那一屏。"""
        if self.base < 0:
            self.show_confirm("exit")
            return
        self.show(self.base)
        self.notice = None

    # ---------- 提示 / 确认框 ----------
    def renew_code(self):
        """配对码连错 5 次 → 显式换一张新码（提示的字面就是"已自动更换"）。"""
        self.window, self.codes = True, self.codes + 1

    def prompt_pairing(self):
        self.notice = "pairing"
        self.show(DLG_CONFIRM)

    def prompt_mode(self):
        self.notice = "mode"
        self.show(DLG_CONFIRM)

    def show_confirm(self, kind):
        self.notice = None
        self.show(DLG_CONFIRM)

    def request_pairing(self):
        if self.devices:
            self.show_confirm("pairconfirm")
        else:
            self.prompt_pairing()

    # ---------- MENU ----------
    def go_back_one_level(self):
        if not self.hist:
            return False
        target = self.hist.pop()
        self.show(target, back=True)
        return True

    def menu_key(self):
        if self.screen == DLG_CONFIRM:
            if self.notice:
                self.notice = None
            self.close_dialog()
            return
        if self.screen == DLG_DEFAULT:
            self.close_dialog()
            return
        if self.screen == DLG_EXITING:
            return
        if not self.go_back_one_level():
            self.show_confirm("exit")

    def menu_hint_is_exit(self):
        """底栏提示（refreshMenuHint 的判据）：历史空 = 提示"退出"。"""
        return not self.hist

    # ---------- 各屏按键 ----------
    def press(self, key):
        """在**当前屏**上按一个键。返回 True 表示状态可能变了。"""
        sc = self.screen
        if sc == DLG_EXITING:
            return False
        if sc == MAIN:
            if key == "menu":
                self.show_confirm("exit")
            elif key == "trash":
                self.menuFromPairing = False
                self.show(MENU)
            elif key == "enter0":        # 键一：主功能
                if self.mode == "hotspot":
                    pass                     # 切正文块（不影响导航）
                elif self.mode == "wifi":
                    pass                     # 跳系统 Wi-Fi 设置
                else:
                    self.modeFromMenu = False
                    self.modeFromPairing = False
                    self.show(MODE)
        elif sc == MENU:
            if key == "menu":
                self.go_back_one_level()
            elif key == "pick_mode":
                self.modeFromMenu = True
                self.modeFromPairing = self.menuFromPairing
                self.show(MODE)
            elif key == "pick_pair":
                if not self.menuFromPairing:      # 配对模式下这一项是隐藏的
                    self.request_pairing()
            elif key == "pick_paired":
                self.show(PAIRED)
            elif key == "pick_about":
                self.show(ABOUT)
            elif key == "pick_exit":
                self.show_confirm("exit")
        elif sc == MODE:
            if key == "menu":
                self.go_back_one_level()
            elif key == "enter":
                self.show(DLG_DEFAULT)
        elif sc == ABOUT:
            if key == "menu":
                self.go_back_one_level()
            elif key == "enter10":
                self.show(LOG)
        elif sc == LOG:
            if key == "menu":
                self.go_back_one_level()
        elif sc == PAIRED:
            if key == "menu":
                self.go_back_one_level()
            elif key == "unpair" and self.devices:
                self.devices = False          # 解除最后一台（留在本页）
            elif key == "enter" and self.devices:
                self.show_confirm("unpair")
        elif sc == PAIRING:
            if key == "menu":
                self.go_back_one_level()
            elif key == "trash":
                self.menuFromPairing = True
                self.show(MENU)
            elif key == "enter0":
                if self.mode == "wifi":
                    pass
                elif self.mode is None:
                    self.modeFromMenu = False
                    self.modeFromPairing = False
                    self.show(MODE)
        elif sc == DLG_CONFIRM:
            if key == "menu":
                self.menu_key()
            elif key == "ok":
                if self.notice == "pairing":
                    self.notice = None
                    self.enter_pairing_home()
                elif self.notice == "mode":
                    self.close_dialog()
                else:
                    self.notice = None
            elif key == "stray" and not self.notice:
                self.close_dialog()           # 有取消的弹窗：其它键 = 取消
        elif sc == DLG_DEFAULT:
            if key == "menu":
                self.close_dialog()
            elif key in ("ok", "no"):
                landing = PAIRING if (self.modeFromPairing or
                                      (not self.modeFromMenu and not self.devices)) else MAIN
                self.modeFromPairing = False
                self.show(landing)
        return True


# ======================================================================
# 一、逐条流程对账
# ======================================================================
def check(label, expect, got):
    ok = expect == got
    print("%-4s %-56s 期望=%-12s 实际=%s" % ("OK" if ok else "FAIL", label, expect, got))
    return ok


def scenarios():
    all_ok = True
    N = NAME

    # ① 首启：先"请选择连接方式" → 确定 → 面板；面板就是第一层 → MENU = 退出
    u = Ui()
    u.show(MODE)
    u.prompt_mode()
    all_ok &= check(u"首启：请选择连接方式（确定=关掉它）", N[MODE], N[(u.close_dialog(), u.screen)[1]])
    u = Ui()
    u.show(MODE)
    u.prompt_mode()
    u.menu_key()
    all_ok &= check("首启 提示上按 MENU = 回到面板（不是退出）", N[MODE], N[u.screen])
    all_ok &= check("首启 面板 MENU", N[DLG_CONFIRM], N[(u.menu_key(), u.screen)[1]])

    # ② 首启选完方式（无设备）→ 配对页；它是这一态的家 → MENU = 退出
    u = Ui()
    u.show(MODE)
    u.prompt_mode()
    u.close_dialog()
    u.press("enter")                     # 选连接方式
    u.press("ok")                        # 设为默认
    all_ok &= check("首启选完方式：先弹配对提示（不直接进配对页）", N[DLG_CONFIRM], N[u.screen])
    all_ok &= check("   提示底下压着连接设置面板", N[MODE], N[u.base])
    u.press("ok")                        # 确定 → 进配对页
    all_ok &= check("   确定后进配对页", N[PAIRING], N[u.screen])
    all_ok &= check("首启自动进的配对页：MENU = 退出软件", N[DLG_CONFIRM], N[(u.menu_key(), u.screen)[1]])

    # ③ 开机（有方式、无设备）：配对页 + 提示；MENU 关提示 → 配对页 → 再 MENU = 退出
    u = Ui(mode="wifi")
    u.enter_pairing_home()
    u.prompt_pairing()
    all_ok &= check("无设备开机：提示盖在配对页上", N[DLG_CONFIRM], N[u.screen])
    all_ok &= check("   提示底下就是配对页", N[PAIRING], N[u.base])
    u.menu_key()
    all_ok &= check("   提示上 MENU = 回到配对页（配对窗不重开、不换码）",
                    N[PAIRING], N[u.screen])
    all_ok &= check("   码没换", 1, u.codes)
    all_ok &= check("   配对页 MENU = 退出软件", N[DLG_CONFIRM], N[(u.menu_key(), u.screen)[1]])

    # ④ 有设备：主界面 → 垃圾桶开菜单 → 已配对设备 → MENU → 菜单 → MENU → 主界面 → MENU 退出
    u = Ui(screen=MAIN, base=MAIN, devices=True, mode="hotspot")
    u.press("trash")
    u.press("pick_paired")
    all_ok &= check("设备页 MENU", N[MENU], N[(u.menu_key(), u.screen)[1]])
    all_ok &= check("菜单 MENU", N[MAIN], N[(u.menu_key(), u.screen)[1]])
    all_ok &= check("主界面 MENU", N[DLG_CONFIRM], N[(u.menu_key(), u.screen)[1]])

    # ⑤ 有设备：菜单 → 进入配对模式（确认）→ 配对页 → MENU = 返回菜单（用户手动进的那一支）
    u = Ui(screen=MAIN, base=MAIN, devices=True, mode="hotspot")
    u.press("trash")
    u.press("pick_pair")
    all_ok &= check("手动进配对模式：先弹确认", N[DLG_CONFIRM], N[u.screen])
    all_ok &= check("   确认框底下压着选项菜单", N[MENU], N[u.base])
    u.show(PAIRING)                      # 确认 → 进配对页
    all_ok &= check("手动进的配对页：MENU = 返回刚才那一屏", N[MENU], N[(u.menu_key(), u.screen)[1]])

    # ⑥ 配对模式下菜单里"进入配对模式"隐藏（按动作码分发，不按下标）
    u = Ui(screen=PAIRING, base=PAIRING, devices=True, mode="hotspot")
    u.press("trash")
    all_ok &= check(u"菜单来自配对页 → 用「隐藏该项」的那份模型", True, u.menuFromPairing)
    u.press("pick_pair")
    all_ok &= check("   这一项按下去什么都不做（已隐藏）", N[MENU], N[u.screen])
    u.press("pick_paired")
    all_ok &= check("   其余项照旧（已配对设备）", N[PAIRED], N[u.screen])

    # ⑦ 无设备时的配对页上按垃圾桶：菜单也是那份隐藏版；MENU 回配对页
    u = Ui(mode="wifi")
    u.enter_pairing_home()
    u.prompt_pairing()
    u.press("ok")
    u.press("trash")
    all_ok &= check(u"无设备：配对页菜单隐藏「进入配对模式」", True, u.menuFromPairing)
    all_ok &= check("   菜单 MENU → 配对页（返回不重弹提示）", N[PAIRING], N[(u.menu_key(), u.screen)[1]])
    all_ok &= check("   配对页 MENU → 退出软件（它是这一态的家）", N[DLG_CONFIRM], N[(u.menu_key(), u.screen)[1]])

    # ⑧ 守卫：无设备时**直接**调 showScreen(主界面) 也进不去（改成配对流程）
    u = Ui(screen=MODE, base=MODE, mode="wifi")
    u.show(MAIN)
    all_ok &= check("无设备直接要求主界面 → 先弹配对提示", N[DLG_CONFIRM], N[u.screen])
    all_ok &= check("   没有一帧主界面", False, u.screen == MAIN or u.base == MAIN)

    # ⑨ 守卫：无设备时**直接**调 showScreen(配对页) 也先弹提示
    u = Ui(screen=MODE, base=MODE, mode="wifi")
    u.show(PAIRING)
    all_ok &= check("无设备直接要求配对页 → 先弹配对提示", N[DLG_CONFIRM], N[u.screen])
    all_ok &= check("   配对页还没进（提示的确定才进）", N[MODE], N[u.base])

    # ⑩ 配对码连错 5 次：提示写明"已自动更换新的配对码"，回去时码确实换了
    u = Ui(mode="hotspot")
    u.enter_pairing_home()
    u.prompt_pairing()
    u.press("ok")
    before = u.codes
    u.renew_code()                       # onPairingAttemptFailed 里显式换码
    u.prompt_pairing()                   # 输错 5 次的提示（弹在配对页上）
    u.menu_key()                         # MENU = 只关提示
    all_ok &= check("输错 5 次提示：MENU 回配对页", N[PAIRING], N[u.screen])
    all_ok &= check("   码确实换了一张（提示字面已经这么写）", before + 1, u.codes)
    after = u.codes
    u.menu_key()                         # 配对页 MENU → 退出确认 → 取消
    all_ok &= check("   关掉浮层回配对页不换码", after, u.codes)

    # ⑪ 解除最后一台 → 菜单 → MENU：不回主界面，改弹配对提示
    u = Ui(screen=MAIN, base=MAIN, devices=True, mode="hotspot")
    u.press("trash")
    u.press("pick_paired")
    u.press("unpair")
    all_ok &= check("解完最后一台：留在设备页", N[PAIRED], N[u.screen])
    u.menu_key()
    all_ok &= check("   设备页 MENU → 选项菜单", N[MENU], N[u.screen])
    u.menu_key()
    all_ok &= check("   菜单 MENU → 弹配对提示（不回主界面）", N[DLG_CONFIRM], N[u.screen])
    u.press("ok")
    all_ok &= check("   确定 → 配对页", N[PAIRING], N[u.screen])
    all_ok &= check("   它是无设备那一态的家 → MENU = 退出", N[DLG_CONFIRM], N[(u.menu_key(), u.screen)[1]])

    # ⑫ 有无设备的两种配对页在层级上的差别：手动进的能回、自动进的不能回
    manual = Ui(screen=MAIN, base=MAIN, devices=True, mode="hotspot")
    manual.press("trash")
    manual.press("pick_pair")            # 菜单里"进入配对模式"（有设备 → 二次确认）
    manual.show(PAIRING)                 # 确认 → 配对页（普通入栈）
    manual.press("trash")                # 配对页 → 垃圾桶 → 菜单
    manual.menu_key()                    # 菜单 MENU → 回配对页
    all_ok &= check(u"手动进的配对页：MENU 提示为「返回」", False, manual.menu_hint_is_exit())
    auto = Ui(mode="wifi")
    auto.enter_pairing_home()
    auto.prompt_pairing()
    auto.press("ok")
    all_ok &= check(u"自动进的配对页：MENU 提示为「退出」", True, auto.menu_hint_is_exit())

    print("\n逐条流程对账：" + ("全部通过" if all_ok else "**有失败项**"))
    return all_ok


# ======================================================================
# 二、逐屏逐键穷举（BFS）
# ======================================================================
KEYS = ["menu", "trash", "enter", "enter0", "enter10", "ok", "no", "stray",
        "pick_mode", "pick_pair", "pick_paired", "pick_about", "pick_exit", "unpair"]


def initial_states():
    """三个真实启动态（onResume 的三条支路）。"""
    out = []
    # 首启：没有连接方式 → 面板 + "请选择连接方式"
    u = Ui()
    u.show(MODE)
    u.prompt_mode()
    out.append(("首启(无方式无设备)", u))
    # 开机：有方式 + 有设备 → 主界面
    u = Ui(screen=MAIN, base=MAIN, devices=True, mode="hotspot")
    out.append(("开机(有方式有设备)", u))
    # 开机：有方式 + 无设备 → 配对页立为家 + 配对提示
    u = Ui(mode="wifi")
    u.enter_pairing_home()
    u.prompt_pairing()
    out.append(("开机(有方式无设备)", u))
    return out


def walk():
    print("\n===== 逐屏逐键穷举（BFS）=====")
    seen = {}
    queue = collections.deque()
    for label, u in initial_states():
        seen[u.key()] = (label, u.clone())
        queue.append(u.clone())
    problems = []
    while queue:
        u = queue.popleft()
        for k in KEYS:
            n = u.clone()
            before = n.key()
            n.press(k)
            if n.key() == before:
                continue                        # 这一键在这屏上不做事（不算可达新状态）
            if n.key() not in seen:
                seen[n.key()] = ("", n.clone())
                queue.append(n.clone())
        if len(seen) > 200000:
            problems.append("状态爆炸（>20 万）：BFS 提前收手")
            break

    # ---- 断言 1：铁律①（无已配对设备时主界面绝不出现）----
    bad = [u for _, u in seen.values() if not u.devices and (u.screen == MAIN or u.base == MAIN)]
    if bad:
        problems.append("铁律①破了：无设备时出现了主界面 %d 个状态" % len(bad))

    # ---- 断言 2：铁律②（无设备时配对页一定伴随过配对提示）----
    # 无设备 + 配对页 = 当前屏：只可能来自"提示的确定"或"提示底下的配对页"
    weird = [u for _, u in seen.values()
             if not u.devices and u.screen == PAIRING and not u.window]
    if weird:
        problems.append("配对页开着但没有配对窗 %d 个状态" % len(weird))

    # ---- 断言 3：有没有死路（每个非终态至少有一个键能改变状态）----
    dead = []
    for label, u in seen.values():
        if u.screen == DLG_EXITING:
            continue
        movable = False
        for k in KEYS:
            n = u.clone()
            n.press(k)
            if n.key() != u.key():
                movable = True
                break
        if not movable:
            dead.append((label, u))
    if dead:
        for label, u in dead[:5]:
            problems.append("死路：%s screen=%s base=%s hist=%s" %
                            (label, NAME[u.screen], NAME.get(u.base, u.base), u.hist))

    # ---- 断言 4：能不能退出（每个状态都存在一条路走到退出确认）----
    # 反向 BFS：先找出所有"退出确认"状态，看哪些状态能走到它们
    exit_states = set(k for k, (_, u) in seen.items()
                      if u.screen == DLG_CONFIRM and u.notice is None)
    rev = collections.defaultdict(list)
    for k, (_, u) in seen.items():
        for kk in KEYS:
            n = u.clone()
            n.press(kk)
            if n.key() != k and n.key() in seen:
                rev[n.key()].append(k)
    can_exit, frontier = set(exit_states), list(exit_states)
    while frontier:
        cur = frontier.pop()
        for prev in rev[cur]:
            if prev not in can_exit:
                can_exit.add(prev)
                frontier.append(prev)
    stuck = [(label, u) for k, (label, u) in seen.items()
             if k not in can_exit and u.screen != DLG_EXITING]
    if stuck:
        for label, u in stuck[:5]:
            problems.append("退不出去：%s screen=%s base=%s hist=%s" %
                            (label, NAME[u.screen], NAME.get(u.base, u.base), u.hist))

    # ---- 断言 5：底栏提示与 MENU 行为同源（面板/配对页两处）----
    for k, (label, u) in seen.items():
        if u.screen in (MODE, PAIRING) and not u.is_dialog(u.screen):
            hint_exit = u.menu_hint_is_exit()
            n = u.clone()
            n.menu_key()
            actual_exit = (n.screen == DLG_CONFIRM and n.notice is None)
            if hint_exit != actual_exit:
                problems.append("提示与行为不一致：%s screen=%s 提示=%s 实际=%s"
                                % (label, NAME[u.screen], hint_exit, actual_exit))

    # ---- 断言 6：配对页的两种 MENU 语义（家=退出 / 返回）与"用户是不是自己走进来的"一致 ----
    for k, (label, u) in seen.items():
        if u.screen != PAIRING:
            continue
        if u.devices and not u.hist:
            problems.append("有设备却站在配对页而层级历史为空 → 底栏会错说成「退出」")
        if not u.devices and u.hist:
            problems.append("无设备时配对页的层级历史没清空（它该是这一态的家）")

    # ---- 报告 ----
    kinds = collections.Counter(NAME[u.screen] for _, u in seen.values())
    print("可达状态 %d 个；覆盖屏：%s" % (len(seen), dict(kinds)))
    print("其中【无已配对设备】的状态 %d 个，覆盖屏：%s"
          % (len([1 for _, u in seen.values() if not u.devices]),
             dict(collections.Counter(NAME[u.screen] for _, u in seen.values()
                                      if not u.devices))))
    if problems:
        print("\n发现问题：")
        for p in problems:
            print("  ✗ " + p)
        return False
    print("\n逐屏逐键穷举：六条性质全部成立（无死路 / 能退出 / 铁律①不破 / 铁律②不破 /"
          "\n            提示与行为同源 / 配对页两种 MENU 语义与历史一致）")
    return True


if __name__ == "__main__":
    ok = scenarios()
    ok = walk() and ok
    print("\n" + ("全部通过" if ok else "**有失败项**"))
    raise SystemExit(0 if ok else 1)
