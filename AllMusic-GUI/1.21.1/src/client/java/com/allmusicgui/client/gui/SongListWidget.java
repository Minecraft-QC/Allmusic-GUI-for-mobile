package com.allmusicgui.client.gui;

import java.util.ArrayList;
import java.util.List;

import com.allmusicgui.model.Song;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 简易可滚动歌单列表（1.21.1 旧渲染：GuiGraphics）。原版风格。
 *
 * 移动端适配（Foldcraft Launcher / Zalith Launcher 2 触摸模拟鼠标）：
 * - 行高 34px，方便手指点选；
 * - 行内操作按钮：右侧「播 / 藏 / 删」小按钮，触摸可直接点（替代右键菜单）；
 * - 支持触摸拖动滚动（mouseDragged），无滚轮也可滚动列表；
 * - 长按（按住 500ms）弹出操作菜单，等价右键。
 */
public class SongListWidget {
	public interface ClickHandler {
		/** button: 0=点击（播放/打开），1=操作菜单（收藏/移除），2=删除 */
		void onClick(Song song, int button);
	}

	/** 操作按钮行为回调：kind 0=播/打开 1=收藏/移除 2=删除 */
	public interface ActionHandler {
		void onAction(Song song, int kind);
	}

	private static final int ROW_H = 34;
	/** 行内按钮宽度（含间距） */
	private static final int BTN_W = 26;
	private static final int BTN_H = 24;
	private static final int BTN_GAP = 2;
	/** 长按触发阈值（毫秒） */
	private static final long LONG_PRESS_MS = 500;
	/** 拖动超过该距离（像素）视为滚动，不再触发点击 */
	private static final int DRAG_SLOP = 6;

	private final Font font;
	private final ClickHandler onClick;
	private final ActionHandler onAction;
	private final List<Song> songs = new ArrayList<>();

	int x, y, w, h;
	private int scroll;
	private int selected = -1;

	// ---------- 触摸拖动/长按状态 ----------
	/** 是否处于按下状态 */
	private boolean pressing;
	private double pressX, pressY;
	/** 按下时的列表内容偏移 */
	private int pressScroll;
	/** 是否已进入拖动模式 */
	private boolean dragging;
	/** 按下时刻（长按判定用） */
	private long pressTime;
	/** 当前按下命中的行索引（-1 = 无） */
	private int pressIdx = -1;
	/** 长按已触发标志（触发后松手不再算点击） */
	private boolean longPressFired;

	public SongListWidget(Font font, ClickHandler onClick) {
		this(font, onClick, null);
	}

	public SongListWidget(Font font, ClickHandler onClick, ActionHandler onAction) {
		this.font = font;
		this.onClick = onClick;
		this.onAction = onAction;
	}

	public void setBounds(int x, int y, int w, int h) {
		this.x = x;
		this.y = y;
		this.w = w;
		this.h = h;
	}

	public void setSongs(List<Song> songs) {
		this.songs.clear();
		this.songs.addAll(songs);
		this.selected = -1;
		this.scroll = 0;
	}

	public Song selectedSong() {
		return (selected >= 0 && selected < songs.size()) ? songs.get(selected) : null;
	}

	// ---------- 渲染 ----------

	public void render(GuiGraphics g, int mx, int my) {
		g.fill(x, y, x + w, y + h, 0x40101010);
		// 滚动条（有溢出时）
		int visible = visibleRows();
		int maxScroll = maxScroll();
		if (maxScroll > 0) {
			int barH = Math.max(18, h * visible / songs.size());
			int barY = y + (h - barH) * scroll / maxScroll;
			g.fill(x + w - 3, barY, x + w, barY + barH, 0x80808080);
		}
		if (songs.isEmpty()) return;

		g.enableScissor(x, y, x + w, y + h);
		for (int i = scroll; i < Math.min(songs.size(), scroll + visible + 1); i++) {
			int ry = y + (i - scroll) * ROW_H;
			Song s = songs.get(i);
			boolean rowHover = mx >= x && mx < x + w && my >= ry && my < ry + ROW_H;
			int bg = rowHover ? 0x30FFFFFF : (i == selected ? 0x50FFFFFF : 0);
			if (bg != 0) g.fill(x, ry, x + w, ry + ROW_H, bg);
			// 偶数行分隔线
			if (i > scroll) g.fill(x, ry, x + w, ry + 1, 0x20FFFFFF);

			// 文本（避开右侧按钮区）
			int textRight = x + w - actionButtonZoneWidth() - 4;
			String num = (i + 1) + ".";
			g.drawString(font, num, x + 4, ry + 4, 0xFF707070);
			int numW = font.width(num) + 4;
			// 标题（截断）
			g.drawString(font, clip(s.name, textRight - (x + 4 + numW)), x + 4 + numW, ry + 3, 0xFFFFFFFF);
			g.drawString(font, clip(s.sub, textRight - (x + 4)), x + 4, ry + 15, 0xFF9B9B9B);
			// 右侧小字：时长+类型
			String right = s.durationText().isEmpty() ? s.type : s.durationText() + " " + s.type;
			g.drawString(font, right, textRight - font.width(right), ry + 15, 0xFF8A8A8A);

			// 行内操作按钮
			renderActionButtons(g, s, ry, mx, my);
		}
		g.disableScissor();
	}

	/** 移动端行内操作按钮区总宽（含边距） */
	public int actionButtonZoneWidth() {
		return hasActionButton() ? BTN_W * 3 + 8 : 4;
	}

	private boolean hasActionButton() {
		return onAction != null;
	}

	private void renderActionButtons(GuiGraphics g, Song s, int ry, int mx, int my) {
		if (onAction == null) return;
		int bx = x + w - BTN_W * 3 - 6;
		int by = ry + (ROW_H - BTN_H) / 2;
		drawMiniButton(g, bx, by, "▶", mx, my, 0xFF55AA55);
		drawMiniButton(g, bx + BTN_W, by, "♥", mx, my, 0xFFDD5555);
		drawMiniButton(g, bx + BTN_W * 2, by, "✕", mx, my, 0xFFAA6666);
	}

	private void drawMiniButton(GuiGraphics g, int bx, int by, String label, int mx, int my, int color) {
		boolean hover = mx >= bx && mx < bx + BTN_W - BTN_GAP && my >= by && my < by + BTN_H;
		int bg = hover ? 0x70FFFFFF : 0x50202020;
		g.fill(bx, by, bx + BTN_W - BTN_GAP, by + BTN_H, bg);
		g.fill(bx, by, bx + BTN_W - BTN_GAP, by + 1, 0x60FFFFFF);
		int tw = font.width(label);
		int tx = bx + (BTN_W - BTN_GAP - tw) / 2;
		int ty = by + (BTN_H - 8) / 2;
		g.drawString(font, label, tx, ty, color);
	}

	/** 简单文本截断（超宽加省略号） */
	private String clip(String text, int maxW) {
		if (maxW <= 8) return "";
		if (font.width(text) <= maxW) return text;
		String ellipsis = "…";
		while (text.length() > 1 && font.width(text + ellipsis) > maxW) {
			text = text.substring(0, text.length() - 1);
		}
		return text + ellipsis;
	}

	// ---------- 命中测试 ----------

	/** 返回行内操作按钮命中：0=播 1=藏 2=删；-1=未命中按钮 */
	private int hitActionButton(double mx, double my) {
		if (onAction == null) return -1;
		if (mx < x + w - BTN_W * 3 - 6 || mx >= x + w) return -1;
		int idx = indexAt(my);
		if (idx < 0 || idx >= songs.size()) return -1;
		int ry = rowY(idx);
		int by = ry + (ROW_H - BTN_H) / 2;
		if (my < by || my >= by + BTN_H) return -1;
		int rel = (int) (mx - (x + w - BTN_W * 3 - 6));
		return rel / BTN_W;
	}

	private int indexAt(double my) {
		int rel = (int) ((my - y) / ROW_H);
		int idx = rel + scroll;
		if (my < y || my >= y + h) return -1;
		if (idx < 0 || idx >= songs.size()) return -1;
		return idx;
	}

	private int rowY(int idx) {
		return y + (idx - scroll) * ROW_H;
	}

	private int visibleRows() {
		return Math.max(1, h / ROW_H);
	}

	private int maxScroll() {
		return Math.max(0, songs.size() - visibleRows());
	}

	// ---------- 交互 ----------

	/**
	 * 按下：记录位置（用于拖动与长按判定），行内按钮立即生效。
	 */
	public boolean mouseClicked(double mx, double my, int button) {
		if (mx < x || mx >= x + w || my < y || my >= y + h) return false;
		// 行内操作按钮：直接触发（触屏友好）
		int act = hitActionButton(mx, my);
		if (act >= 0) {
			Song s = songs.get(indexAt(my));
			if (onAction != null) onAction.onAction(s, act);
			else if (onClick != null) onClick.onClick(s, button);
			return true;
		}
		if (button == 0) {
			pressing = true;
			dragging = false;
			longPressFired = false;
			pressX = mx;
			pressY = my;
			pressScroll = scroll;
			pressTime = System.currentTimeMillis();
			pressIdx = indexAt(my);
			// 选中但不激活：等松手或长按。这里立即设置 selected 供视觉反馈
			if (pressIdx >= 0) selected = pressIdx;
			return true;
		}
		// 右键：操作菜单（桌面端保留兼容）
		if (button == 1) {
			int idx = indexAt(my);
			if (idx >= 0 && onClick != null) {
				onClick.onClick(songs.get(idx), 1);
				return true;
			}
		}
		return false;
	}

	/**
	 * 拖动：触摸滚动列表。拖动距离超过 DRAG_SLOP 后进入滚动模式。
	 */
	public boolean mouseDragged(double mx, double my, int button) {
		if (!pressing) return false;
		if (!dragging) {
			if (Math.abs(my - pressY) > DRAG_SLOP || Math.abs(mx - pressX) > DRAG_SLOP) {
				dragging = true;
				longPressFired = true; // 拖动则取消长按/点击
			}
		}
		if (dragging) {
			int dy = (int) (my - pressY);
			int moved = -dy / ROW_H;
			int target = Math.max(0, Math.min(maxScroll(), pressScroll + moved));
			if (target != scroll) {
				scroll = target;
			}
			return true;
		}
		return false;
	}

	/**
	 * 松手：短按=点击激活；长按已触发菜单则忽略。
	 */
	public boolean mouseReleased(double mx, double my, int button) {
		if (!pressing) return false;
		pressing = false;
		boolean wasDragging = dragging;
		dragging = false;
		if (!wasDragging && !longPressFired && pressIdx >= 0 && pressIdx < songs.size()) {
			int idx = indexAt(my);
			// 松手仍在同一行 → 触发点击
			if (idx == pressIdx && onClick != null) {
				onClick.onClick(songs.get(idx), 0);
				return true;
			}
		}
		pressIdx = -1;
		return wasDragging;
	}

	/** 屏幕 tick：驱动长按检测（由宿主 Screen 每帧调用） */
	public void tick(long nowMillis) {
		if (pressing && !dragging && !longPressFired
				&& System.currentTimeMillis() - pressTime >= LONG_PRESS_MS) {
			longPressFired = true;
			if (pressIdx >= 0 && pressIdx < songs.size() && onClick != null) {
				onClick.onClick(songs.get(pressIdx), 1); // 长按 = 操作菜单
			}
		}
	}

	public boolean mouseScrolled(double amount) {
		if (songs.isEmpty()) return false;
		scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.signum(amount) * 3));
		return true;
	}

	// ---------- 键盘/手柄导航（Controlify D-pad 映射方向键） ----------

	public boolean hasSongs() { return !songs.isEmpty(); }
	public boolean hasSelection() { return selected >= 0 && selected < songs.size(); }

	/** 方向键上移选中项，自动滚动。 */
	public void moveUp() {
		if (songs.isEmpty()) return;
		if (selected < 0) { selected = songs.size() - 1; }
		else if (selected > 0) { selected--; }
		ensureVisible();
	}

	/** 方向键下移选中项，自动滚动。 */
	public void moveDown() {
		if (songs.isEmpty()) return;
		if (selected < 0) { selected = 0; }
		else if (selected < songs.size() - 1) { selected++; }
		ensureVisible();
	}

	private void ensureVisible() {
		int visible = visibleRows();
		if (selected < scroll) scroll = selected;
		else if (selected >= scroll + visible) scroll = selected - visible + 1;
		int maxScroll = maxScroll();
		scroll = Math.max(0, Math.min(maxScroll, scroll));
	}

	/** 触发左键（播放/打开）。 */
	public void activateSelected() {
		if (hasSelection() && onClick != null) onClick.onClick(songs.get(selected), 0);
	}
}
