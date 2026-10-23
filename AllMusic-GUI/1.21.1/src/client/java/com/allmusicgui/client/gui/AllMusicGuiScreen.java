package com.allmusicgui.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.allmusicgui.client.MusicCommand;
import com.allmusicgui.client.netease.NeteaseApi;
import com.allmusicgui.client.playlist.PlaylistStore;
import com.allmusicgui.model.SearchType;
import com.allmusicgui.model.Song;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * AllMusic 点歌界面（1.21.1 旧渲染：GuiGraphics 直接绘制）。
 * 顶部输入框；中间内容；底部五页签：搜索 / 歌单(本地多歌单) / 云歌单(同步) / 指令 / 我的。
 * - 搜索歌手/专辑/歌单后可下钻（二级菜单，ESC 返回）
 * - 点击行 = 点歌/打开；行内按钮 ♥ 收藏 / ✕ 删除；长按行 = 收藏菜单（移动端）
 * - 本地歌单为多歌单，默认「喜欢」歌单，可新建/删除
 *
 * 移动端适配（Foldcraft Launcher / Zalith Launcher 2 触摸模拟鼠标）：
 * - 响应式宽度：窄屏不再固定 460px，自动铺满（留 8px 边距）
 * - 顶部 ✕ 关闭/返回按钮：无 ESC 键也能返回上级与关闭界面
 * - 按钮全部加高（24px），页签加高（28px），行高 34px，手指易点
 * - 列表支持触摸拖动滚动 + 长按菜单（见 SongListWidget）
 * - 「我的」页窄屏改为上下单列布局，避免 250px 双栏挤压
 */
public class AllMusicGuiScreen extends Screen {
	public enum Tab { SEARCH, LOCAL, PLAYLIST, COMMAND, MINE }

	private static final int MIN_CONTENT_W = 240;
	private static final int MAX_CONTENT_W = 520;
	private static final int TAB_H = 28;
	/** 触屏按钮标准高度 */
	private static final int BTN_H = 24;
	/** 窄屏判定：屏幕宽度不足时启用单列布局（「我的」页）与更紧凑间距 */
	private static final int NARROW_W = 420;
	private static final int GLFW_KEY_ENTER = 257;
	private static final int GLFW_KEY_KP_ENTER = 335;
	private static final int GLFW_KEY_ESCAPE = 256;
	private static final int GLFW_KEY_UP = 265;
	private static final int GLFW_KEY_DOWN = 264;
	private static final int GLFW_KEY_DELETE = 261;
	private static final int GLFW_KEY_BACKSPACE = 259;
	private static final int POPUP_W = 300;
	private static final int POPUP_MAX_ROWS = 8;

	private final PlaylistStore store = new PlaylistStore();
	private Tab tab = Tab.SEARCH;
	private String status = "点行播放 · ♥ 收藏 · ✕ 删除 · 长按更多";

	private String keyword = "";
	private String playlistId = "";
	private SearchType searchType = SearchType.SONG;

	private boolean closeAfterPlay = true;
	private boolean overwriteSync = true;
	private boolean loginMode = false;
	private final List<Song> myPlaylists = new ArrayList<>();
	private boolean autoTried = false;

	private int yMyCloudLabel;
	private int ySyncedLabel;
	private int ySyncedListTop;

	/** 搜索结果（歌手/专辑/歌单/单曲），切页签后保留 */
	private final List<Song> searchResults = new ArrayList<>();
	/** 下钻后的歌曲列表（点歌手/专辑/歌单进入） */
	private final List<Song> drillSongs = new ArrayList<>();
	private boolean drilling = false;
	private boolean hasSearched = false;
	/** 搜索竞态防护：只有最新一次搜索的响应会被应用 */
	private int searchSeq = 0;

	private final List<Song> displaySongs = new ArrayList<>();
	private String listTitle = "搜索结果";
	private Song lastClicked;

	// 多歌单状态
	/** LOCAL 页当前打开的歌单 id；null = 歌单列表一级 */
	private String currentPlaylistId;
	/** 右键单曲后的歌单选择弹窗 */
	private boolean pickingPlaylist = false;
	private Song pendingSong;
	/** 新建歌单命名模式 */
	private boolean namingPlaylist = false;
	private String pendingName = "";

	private EditBox inputBox;
	private EditBox cookieBox;
	private EditBox nameBox;
	private SongListWidget list;
	private final List<Button> typeButtons = new ArrayList<>();

	public AllMusicGuiScreen() {
		super(Component.literal("AllMusic 点歌"));
	}

	private boolean isLoggedIn() {
		return store.uid() != 0 && !store.cookie().isEmpty();
	}

	@Override
	protected void init() {
		clearWidgets();
		if (!store.cookie().isEmpty()) {
			NeteaseApi.setCookie(store.cookie());
		}
		int cx = width / 2;
		int lx = cx - contentW() / 2;
		int cw = contentW();

		// 移动端：右上角 ✕ 返回/关闭按钮（替代 ESC）
		addRenderableWidget(Button.builder(Component.literal("✕"), b -> onBackButton())
				.bounds(width - 30, 4, 26, 22).build());

		boolean needInput = (tab == Tab.SEARCH || tab == Tab.PLAYLIST);
		if (needInput) {
			inputBox = new EditBox(font, lx + 4, 10, cw - 84, 22, Component.literal("输入"));
			inputBox.setMaxLength(128);
			addRenderableWidget(inputBox);
		} else {
			inputBox = null;
		}

		switch (tab) {
			case SEARCH -> {
				inputBox.setValue(keyword);
				inputBox.setHint(Component.literal("搜索歌名 / 歌手 / 专辑 / 歌单…"));
				// 移动端：搜索按钮（无需回车）
				addRenderableWidget(Button.builder(Component.literal("搜索"), b -> doSearch())
						.bounds(lx + cw - 76, 36, 76, BTN_H).build());
				// 焦点统一由 setInitialFocus() 决定
				typeButtons.clear();
				if (!pickingPlaylist && !namingPlaylist) {
					SearchType[] types = SearchType.values();
					int tbW = Math.min(80, (cw - 4) / types.length);
					for (int i = 0; i < types.length; i++) {
						final SearchType st = types[i];
						Button b = Button.builder(Component.literal(st.label), btn -> {
									searchType = st;
									refreshTypeButtons();
									doSearch();
								})
								.bounds(lx + i * tbW, 36 + BTN_H + 2, tbW - 2, BTN_H).build();
						typeButtons.add(b);
						addRenderableWidget(b);
					}
					refreshTypeButtons();
				}
				if (drilling) {
					addRenderableWidget(Button.builder(Component.literal("← 返回"), b -> backToSearch())
							.bounds(lx, 36, 60, BTN_H).build());
				}
				listTitle = drilling ? "结果" : "搜索结果";
			}
			case LOCAL -> {
				buildLocalPage(lx);
			}
			case PLAYLIST -> {
				inputBox.setValue(playlistId);
				inputBox.setHint(Component.literal("输入网易云歌单 ID（数字）"));
				addRenderableWidget(Button.builder(Component.literal("同步"), b -> doSync())
						.bounds(lx + cw - 76, 36, 76, BTN_H).build());
				listTitle = "云歌单（输入 ID 同步）";
			}
			case COMMAND -> {
				buildCommandPage(lx);
			}
			case MINE -> {
				buildMinePage(lx);
				return;
			}
		}

		int listTop = 64 + BTN_H + 2; // 输入框 + 搜索/类型按钮两行
		if (tab == Tab.SEARCH) {
			list = new SongListWidget(font, this::onSongClick, this::onSongAction);
			list.setBounds(lx, listTop, cw, Math.max(80, height - listTop - TAB_H - 40));
			displaySongs.clear();
			displaySongs.addAll(drilling ? drillSongs : searchResults);
			list.setSongs(displaySongs);
		} else if (tab == Tab.LOCAL) {
			list = new SongListWidget(font, this::onSongClick, this::onSongAction);
			list.setBounds(lx, 64, cw, Math.max(80, height - 64 - TAB_H - 40));
			displaySongs.clear();
			if (currentPlaylistId == null) {
				// 一级：歌单卡片列表（伪 Song）
				List<PlaylistStore.LocalPlaylist> pls = store.localPlaylists();
				for (int i = 0; i < pls.size(); i++) {
					PlaylistStore.LocalPlaylist p = pls.get(i);
					displaySongs.add(new Song(i, p.name, p.songs.size() + " 首", "本地歌单", 0));
				}
			} else {
				PlaylistStore.LocalPlaylist pl = store.byId(currentPlaylistId);
				if (pl != null) displaySongs.addAll(pl.songs);
			}
			list.setSongs(displaySongs);
		} else if (tab == Tab.PLAYLIST) {
			list = new SongListWidget(font, this::onSongClick, this::onSongAction);
			list.setBounds(lx, 64, cw, Math.max(80, height - 64 - TAB_H - 40));
			displaySongs.clear();
			list.setSongs(displaySongs);
		}

		if (pickingPlaylist || namingPlaylist) {
			buildPopup();
		}

		addTabButtons(lx);
	}

	/** 响应式内容宽度：窄屏铺满（留边距），宽屏限 520 */
	private int contentW() {
		return Math.max(MIN_CONTENT_W, Math.min(MAX_CONTENT_W, width - 16));
	}

	/** 移动端 ✕ 按钮：按当前状态返回上级或关闭界面（等价 ESC） */
	private void onBackButton() {
		if (pickingPlaylist) {
			pickingPlaylist = false;
			pendingSong = null;
			init();
			return;
		}
		if (namingPlaylist) {
			namingPlaylist = false;
			if (pendingSong != null) pickingPlaylist = true;
			init();
			return;
		}
		if (tab == Tab.LOCAL && currentPlaylistId != null) {
			currentPlaylistId = null;
			init();
			return;
		}
		if (tab == Tab.SEARCH && drilling) {
			backToSearch();
			return;
		}
		Minecraft.getInstance().setScreen(null); // 一级：关闭界面
	}

	private int mineTop() { return 64; }
	private int mineBottom() { return height - TAB_H - 38; }

	/** 是否有弹窗打开（歌单选择 / 新建命名）。弹窗期间底层列表不应响应悬停与点击。 */
	private boolean popupOpen() {
		return pickingPlaylist || namingPlaylist;
	}

	// ---------- 指令页 ----------

	/** 快捷指令：全部为 AllMusic 普通玩家可用指令，不含管理员指令。 */
	private record QuickCommand(String label, String args, String hint, boolean toggle) {
	}

	private static final List<QuickCommand> QUICK_COMMANDS = List.of(
			new QuickCommand("静音开关", "mute", "静音已切换（再点一次恢复）", true),
			new QuickCommand("发起切歌投票", "vote", "已发起切歌投票，等待其他玩家同意", false),
			new QuickCommand("同意切歌", "agree", "已同意当前的切歌投票", false),
			new QuickCommand("取消我的投票", "vote cancel", "已取消你发起的切歌投票", false),
			new QuickCommand("查看队列", "list", "队列已显示在聊天栏", false),
			new QuickCommand("取消我的点歌", "cancel", "已取消你点的那首歌", false),
			new QuickCommand("全部界面开关", "hud enable", "全部 HUD 已切换", true),
			new QuickCommand("歌词开关", "hud lyric enable", "歌词显示已切换", true),
			new QuickCommand("歌曲信息开关", "hud info enable", "歌曲信息显示已切换", true),
			new QuickCommand("播放状态开关", "hud state enable", "播放状态显示已切换", true),
			new QuickCommand("专辑图开关", "hud pic enable", "专辑封面显示已切换", true),
			new QuickCommand("重置界面", "hud reset", "HUD 位置与样式已恢复默认", false));

	private void buildCommandPage(int lx) {
		list = null; // 指令页没有歌曲列表，避免沿用上一页的控件
		int cw = contentW();
		int cols = 2;
		int gap = 8;
		int btnW = (cw - gap * (cols - 1)) / cols;
		int rowH = 30;
		int y = 54;
		for (int i = 0; i < QUICK_COMMANDS.size(); i++) {
			QuickCommand qc = QUICK_COMMANDS.get(i);
			int col = i % cols;
			int row = i / cols;
			int bx = lx + col * (btnW + gap);
			int by = y + row * rowH;
			String text = (qc.toggle() ? "⇄ " : "▶ ") + qc.label();
			addRenderableWidget(Button.builder(Component.literal(text), b -> {
						MusicCommand.run(qc.args(), qc.label());
						status = qc.hint();
					})
					.bounds(bx, by, btnW, BTN_H).build());
		}
	}

	private void renderCommandPage(GuiGraphics g, int lx) {
		int cw = contentW();
		g.drawString(font, "快捷指令（普通玩家可用）", lx, 40, 0xFFA0A0A0);
		int cols = 2;
		int rowH = 30;
		int hintY = 54 + ((QUICK_COMMANDS.size() + cols - 1) / cols) * rowH + 6;
		g.drawString(font, "⇄ 为开关：再点一次恢复；▶ 为执行一次", lx, hintY, 0xFF8F8F8F);
		g.drawString(font, "静音与 HUD 开关保存在服务端，换服需重新设置", lx, hintY + 12, 0xFF6E6E6E);
	}

	private void buildLocalPage(int lx) {
		int cw = contentW();
		if (namingPlaylist) return;
		if (currentPlaylistId == null) {
			addRenderableWidget(Button.builder(Component.literal("＋ 新建歌单"), b -> {
						namingPlaylist = true;
						pendingSong = null;
						pendingName = "";
						pickingPlaylist = false;
						init();
					})
					.bounds(lx, 36, Math.min(110, cw), BTN_H).build());
			listTitle = "本地歌单（" + store.localPlaylists().size() + " 个）· 点行进入";
		} else {
			PlaylistStore.LocalPlaylist pl = store.byId(currentPlaylistId);
			addRenderableWidget(Button.builder(Component.literal("← 返回"), b -> {
						currentPlaylistId = null;
						init();
					})
					.bounds(lx, 36, 74, BTN_H).build());
			addRenderableWidget(Button.builder(Component.literal("清空歌单"), b -> {
						if (pl != null) store.clearPlaylist(pl.id);
						status = "已清空歌单";
						init();
					})
					.bounds(lx + 78, 36, 90, BTN_H).build());
			if (!PlaylistStore.LIKE_ID.equals(currentPlaylistId)) {
				addRenderableWidget(Button.builder(Component.literal("删除歌单"), b -> {
							store.removePlaylist(currentPlaylistId);
							currentPlaylistId = null;
							status = "已删除歌单";
							init();
						})
						.bounds(lx + 172, 36, 90, BTN_H).build());
			}
			// 移动端提示：不再依赖右键移除，行内 ✕ 按钮即可
			listTitle = (pl != null ? pl.name + "（" + pl.songs.size() + " 首）" : "本地歌单") + " · ✕ 移除";
		}
	}

	private void buildMinePage(int lx) {
		int cw = contentW();
		int top = mineTop();
		int bottom = mineBottom();
		boolean narrow = width < NARROW_W;

		// 移动端：登录/退出按钮放大置顶
		addRenderableWidget(Button.builder(
						Component.literal(isLoggedIn() ? "退出登录" : (loginMode ? "取消登录" : "网易云登录")), b -> {
							if (isLoggedIn()) {
								store.clearAccount();
								NeteaseApi.setCookie(null);
								myPlaylists.clear();
								status = "已退出网易云登录";
							} else {
								loginMode = !loginMode;
							}
							init();
						})
				.bounds(lx, top, Math.min(120, cw), BTN_H).build());

		// 喜欢列表（移动端：与设置上下布局，不再挤双栏）
		int leftX = lx;
		int leftW = narrow ? cw : cw * 2 / 5;
		int listTop = top + BTN_H + 26;
		int listH = narrow ? Math.max(60, (bottom - listTop) * 2 / 5) : Math.max(60, bottom - listTop);
		list = new SongListWidget(font, this::onSongClick, this::onSongAction);
		list.setBounds(leftX, listTop, leftW, listH);
		displaySongs.clear();
		PlaylistStore.LocalPlaylist like = store.byId(PlaylistStore.LIKE_ID);
		if (like != null) displaySongs.addAll(like.songs);
		list.setSongs(displaySongs);

		// 右栏（设置）：窄屏放到列表下方
		int rx = narrow ? lx : lx + leftW + 10;
		int rw = narrow ? cw : cw - leftW - 10;
		int y = narrow ? listTop + listH + 24 : top + BTN_H + 26;

		if (loginMode && !isLoggedIn()) {
			cookieBox = new EditBox(font, rx, y, rw, 20, Component.literal("MUSIC_U Cookie"));
			cookieBox.setMaxLength(512);
			cookieBox.setHint(Component.literal("粘贴 music.163.com 的 Cookie（含 MUSIC_U）"));
			addRenderableWidget(cookieBox);
			y += 26;
			addRenderableWidget(Button.builder(Component.literal("验证并登录"), b -> doLogin(cookieBox.getValue().trim()))
					.bounds(rx, y, rw / 2 - 4, BTN_H).build());
			addRenderableWidget(Button.builder(Component.literal("取消"), b -> {
						loginMode = false;
						init();
					})
					.bounds(rx + rw / 2 + 4, y, rw / 2 - 4, BTN_H).build());
			y += BTN_H + 6;
		}

		yMyCloudLabel = y;
		y += 16;
		if (isLoggedIn()) {
			addRenderableWidget(Button.builder(Component.literal("刷新我的云歌单"), b -> loadMyPlaylists())
					.bounds(rx, y, Math.min(160, rw), BTN_H).build());
			y += BTN_H + 4;
			for (Song pl : myPlaylists) {
				final long pid = pl.id;
				final String pname = pl.name;
				// 移动端：歌单整行大按钮，右侧同步小按钮
				addRenderableWidget(Button.builder(Component.literal("▸ " + pname), b -> openPlaylist(pid, pname))
						.bounds(rx, y, rw - 88, BTN_H).build());
				addRenderableWidget(Button.builder(Component.literal("同步"), b -> openPlaylist(pid, pname))
						.bounds(rx + rw - 84, y, 84, BTN_H).build());
				y += BTN_H + 2;
			}
			y += 6;
			if (myPlaylists.isEmpty() && !autoTried) {
				autoTried = true;
				loadMyPlaylists();
			}
		}

		ySyncedLabel = y;
		y += 14;
		ySyncedListTop = y;
		List<PlaylistStore.SyncedPlaylist> synced = store.syncedPlaylists();
		for (PlaylistStore.SyncedPlaylist sp : synced) {
			final long id = sp.id;
			final String nm = sp.name;
			// 移动端：同步歌单整行按钮 + 移除小按钮
			addRenderableWidget(Button.builder(Component.literal(nm + "（" + sp.songs.size() + "）"), b -> {
						PlaylistStore.SyncedPlaylist s2 = store.syncedPlaylists().stream()
								.filter(x -> x.id == id).findFirst().orElse(null);
						if (s2 != null) openPlaylist(s2.id, s2.name);
					})
					.bounds(rx, y, rw - 66, BTN_H).build());
			addRenderableWidget(Button.builder(Component.literal("移除"), b -> {
						store.removeSynced(id);
						status = "已移除同步歌单：" + nm;
						init();
					})
					.bounds(rx + rw - 62, y, 62, BTN_H).build());
			y += BTN_H + 2;
		}
		y += 8;

		addRenderableWidget(Button.builder(Component.literal(toggleText(closeAfterPlay, "点歌后自动关闭")), b -> {
					closeAfterPlay = !closeAfterPlay;
					init();
				})
				.bounds(rx, y, rw, BTN_H).build());
		y += BTN_H + 4;
		addRenderableWidget(Button.builder(Component.literal(toggleText(overwriteSync, "同步覆盖同名歌单")), b -> {
					overwriteSync = !overwriteSync;
					init();
				})
				.bounds(rx, y, rw, BTN_H).build());

		addTabButtons(lx);
	}

	private void addTabButtons(int lx) {
		int cw = contentW();
		Tab[] tabs = { Tab.SEARCH, Tab.LOCAL, Tab.PLAYLIST, Tab.COMMAND, Tab.MINE };
		String[] labels = { "搜索", "歌单", "云歌单", "指令", "我的" };
		int tabW = cw / tabs.length;
		int ty = height - TAB_H - 8;
		for (int i = 0; i < tabs.length; i++) {
			final Tab t = tabs[i];
			Button b = Button.builder(Component.literal(labels[i]), btn -> setTab(t))
					.bounds(lx + i * tabW, ty, tabW - 1, TAB_H).build();
			// 当前页签高亮提示
			if (t == tab) b.setMessage(Component.literal("● " + labels[i]));
			addRenderableWidget(b);
		}
	}

	private static String toggleText(boolean on, String label) {
		return (on ? "[√] " : "[×] ") + label;
	}

	private void setTab(Tab t) {
		if (tab == t) return;
		pickingPlaylist = false;
		namingPlaylist = false;
		pendingSong = null;
		currentPlaylistId = null;
		tab = t;
		init();
	}

	/** 行点击（列表行主体）：0=播放/打开（来自触摸短按/鼠标左键），1=操作菜单（长按/右键） */
	private void onSongClick(Song s, int button) {
		lastClicked = s;
		// 歌单一级列表：进入歌单
		if (tab == Tab.LOCAL && currentPlaylistId == null) {
			if (button == 0) {
				int idx = (int) s.id;
				List<PlaylistStore.LocalPlaylist> pls = store.localPlaylists();
				if (idx >= 0 && idx < pls.size()) {
					currentPlaylistId = pls.get(idx).id;
					init();
				}
			} else {
				status = "点击歌单进入，行内 ✕ 可删除歌单";
			}
			return;
		}
		if (button == 1) {
			// 移动端长按/桌面右键：单曲 → 收藏菜单；歌手/专辑/歌单 → 保存为本地歌单
			if (tab == Tab.LOCAL && currentPlaylistId != null) {
				store.removeFromPlaylist(currentPlaylistId, s);
				status = "已从歌单移除：" + s.name;
				init();
				return;
			}
			if (Song.TYPE_SONG.equals(s.type)) {
				pendingSong = s;
				pickingPlaylist = true;
				namingPlaylist = false;
				init();
				return;
			}
			if (Song.TYPE_ARTIST.equals(s.type) || Song.TYPE_ALBUM.equals(s.type) || Song.TYPE_PLAYLIST.equals(s.type)) {
				saveAsPlaylist(s);
				return;
			}
			status = "无法收藏：" + s.type;
			return;
		}
		if (Song.TYPE_SONG.equals(s.type)) {
			MusicCommand.request(s);
			status = "已发送点歌：" + s.name + "（/music " + s.id + "）";
			if (closeAfterPlay) {
				Minecraft.getInstance().setScreen(null);
			}
		} else if (Song.TYPE_ARTIST.equals(s.type)) {
			drillDown(s.id, s.name, NeteaseApi::artistSongs);
		} else if (Song.TYPE_ALBUM.equals(s.type)) {
			drillDown(s.id, s.name, NeteaseApi::album);
		} else if (Song.TYPE_PLAYLIST.equals(s.type)) {
			if (tab == Tab.PLAYLIST) {
				openPlaylist(s.id, s.name);
			} else {
				drillDown(s.id, s.name, NeteaseApi::playlist);
			}
		} else {
			status = s.type + "不可直接点歌：" + s.name;
		}
	}

	/** 行内操作按钮（移动端）：kind 0=▶播放/打开 1=♥收藏 2=✕删除/移除 */
	private void onSongAction(Song s, int kind) {
		lastClicked = s;
		switch (kind) {
			case 0 -> onSongClick(s, 0); // ▶ 与行点击一致
			case 1 -> { // ♥ 收藏
				if (tab == Tab.LOCAL && currentPlaylistId == null) {
					int idx = (int) s.id;
					List<PlaylistStore.LocalPlaylist> pls = store.localPlaylists();
					if (idx >= 0 && idx < pls.size()) {
						currentPlaylistId = pls.get(idx).id;
						init();
					}
					return;
				}
				if (Song.TYPE_SONG.equals(s.type)) {
					pendingSong = s;
					pickingPlaylist = true;
					namingPlaylist = false;
					init();
				} else if (Song.TYPE_ARTIST.equals(s.type) || Song.TYPE_ALBUM.equals(s.type) || Song.TYPE_PLAYLIST.equals(s.type)) {
					saveAsPlaylist(s);
				} else {
					status = "无法收藏：" + s.type;
				}
			}
			case 2 -> { // ✕ 删除
				if (tab == Tab.LOCAL && currentPlaylistId == null) {
					// 歌单一级：删除整个歌单
					int idx = (int) s.id;
					List<PlaylistStore.LocalPlaylist> pls = store.localPlaylists();
					if (idx >= 0 && idx < pls.size() && !PlaylistStore.LIKE_ID.equals(pls.get(idx).id)) {
						store.removePlaylist(pls.get(idx).id);
						status = "已删除歌单：" + pls.get(idx).name;
						init();
					} else {
						status = "「喜欢」默认歌单不可删除";
					}
				} else if (tab == Tab.LOCAL && currentPlaylistId != null) {
					store.removeFromPlaylist(currentPlaylistId, s);
					status = "已从歌单移除：" + s.name;
					init();
				} else if (Song.TYPE_SONG.equals(s.type)) {
					status = "搜索结果不可删除，请到歌单中操作";
				} else {
					status = "该条目不可删除";
				}
			}
		}
	}

	/** 右键保存：把歌手/专辑/歌单内容拉下来存为新的本地歌单 */
	private void saveAsPlaylist(Song s) {
		status = "正在保存 “" + s.name + "” 为本地歌单…";
		Function<Long, CompletableFuture<NeteaseApi.NeteaseResult>> loader;
		if (Song.TYPE_ARTIST.equals(s.type)) {
			loader = NeteaseApi::artistSongs;
		} else if (Song.TYPE_ALBUM.equals(s.type)) {
			loader = NeteaseApi::album;
		} else {
			loader = NeteaseApi::playlist;
		}
		loader.apply(s.id).thenAccept(r -> Minecraft.getInstance().execute(() -> {
			if (r.ok() && !r.songs().isEmpty()) {
				PlaylistStore.LocalPlaylist pl = store.createPlaylist(s.name);
				for (Song x : r.songs()) {
					store.addToPlaylist(pl.id, x);
				}
				status = "已保存为本地歌单 “" + pl.name + "”（" + r.songs().size() + " 首）";
			} else if (r.ok()) {
				status = "保存失败：内容为空";
			} else {
				status = "保存失败：" + r.error();
			}
		}));
	}

	/** 下钻：点歌手/专辑/歌单后在搜索页内展示其歌曲列表 */
	private void drillDown(long id, String name, Function<Long, CompletableFuture<NeteaseApi.NeteaseResult>> loader) {
		drilling = true;
		drillSongs.clear();
		if (list != null) list.setSongs(drillSongs);
		listTitle = name;
		status = "正在加载 “" + name + "” …";
		loader.apply(id).thenAccept(r -> Minecraft.getInstance().execute(() -> {
			if (r.ok()) {
				drillSongs.clear();
				drillSongs.addAll(r.songs());
				listTitle = name + "（" + r.songs().size() + " 首）";
				status = "加载完成，左键点歌/右键收藏";
			} else {
				drilling = false;
				listTitle = "搜索结果";
				status = "加载失败：" + r.error();
			}
			init();
		}));
	}

	private void backToSearch() {
		drilling = false;
		drillSongs.clear();
		listTitle = "搜索结果";
		status = "已返回搜索结果";
		init();
	}

	private void removeSelectedFromLocal() {
		Song s = list != null ? list.selectedSong() : null;
		if (s == null) {
			status = "请先选择要移除的歌曲";
			return;
		}
		store.removeFromPlaylist(currentPlaylistId, s);
		status = "已移除：" + s.name;
		init();
	}

	private void doSearch() {
		keyword = inputBox.getValue().trim();
		if (keyword.isEmpty()) {
			status = "请输入搜索关键词（歌名/歌手/专辑/歌单名）";
			return;
		}
		hasSearched = true;
		drilling = false;
		searchResults.clear();
		displaySongs.clear();
		if (list != null) list.setSongs(displaySongs);
		status = "正在搜索 “" + keyword + "” …";
		final int seq = ++searchSeq;
		NeteaseApi.search(keyword, searchType, 30).thenAccept(r -> Minecraft.getInstance().execute(() -> {
			if (seq != searchSeq) return; // 过期响应丢弃，避免覆盖下钻内容
			if (r.ok()) {
				searchResults.clear();
				searchResults.addAll(r.songs());
				displaySongs.clear();
				displaySongs.addAll(searchResults);
				if (list != null) list.setSongs(displaySongs);
				listTitle = "搜索结果";
				status = r.songs().isEmpty() ? "没有找到结果" : "找到 " + r.songs().size() + " 条（左键打开/点歌，右键收藏）";
			} else {
				status = "搜索失败：" + r.error();
			}
		}));
	}

	private void doSync() {
		playlistId = inputBox.getValue().trim();
		long id;
		try {
			id = Long.parseLong(playlistId);
		} catch (Exception e) {
			status = "歌单 ID 格式不正确";
			return;
		}
		openPlaylist(id, "歌单 #" + id);
	}

	/** 打开网易云歌单：拉取歌曲、存为同步歌单，并切到云歌单页展示 */
	private void openPlaylist(long id, String name) {
		displaySongs.clear();
		if (list != null) list.setSongs(displaySongs);
		listTitle = name;
		status = "正在加载歌单 “" + name + "” …";
		NeteaseApi.playlist(id).thenAccept(r -> Minecraft.getInstance().execute(() -> {
			if (r.ok()) {
				displaySongs.clear();
				displaySongs.addAll(r.songs());
				if (list != null) list.setSongs(displaySongs);
				PlaylistStore.SyncedPlaylist sp = new PlaylistStore.SyncedPlaylist();
				sp.id = id;
				sp.name = name;
				sp.songs = new ArrayList<>(r.songs());
				store.saveSynced(sp, overwriteSync);
				listTitle = name + "（" + r.songs().size() + " 首）";
				status = "歌单加载完成，左键点歌/右键收藏";
			} else {
				status = "歌单加载失败：" + r.error();
			}
			tab = Tab.PLAYLIST;
			init();
		}));
	}

	private void doLogin(String cookie) {
		if (cookie.isEmpty()) {
			status = "请先粘贴 Cookie";
			return;
		}
		status = "正在验证登录…";
		NeteaseApi.setCookie(cookie);
		NeteaseApi.accountInfo().thenAccept(a -> Minecraft.getInstance().execute(() -> {
			if (a.ok()) {
				store.saveAccount(cookie, a.nickname(), a.uid());
				loginMode = false;
				autoTried = false;
				status = "登录成功：" + a.nickname() + "，正在同步云歌单…";
				loadMyPlaylists();
			} else {
				NeteaseApi.setCookie(store.cookie());
				status = "登录失败：" + a.error();
			}
		}));
	}

	private void loadMyPlaylists() {
		if (!isLoggedIn()) return;
		status = "正在拉取你的歌单…";
		NeteaseApi.myPlaylists(store.uid()).thenAccept(r -> Minecraft.getInstance().execute(() -> {
			if (r.ok()) {
				myPlaylists.clear();
				myPlaylists.addAll(r.songs());
				status = "拉取到 " + r.songs().size() + " 个歌单，点击 ▸ 打开";
			} else {
				status = "拉取失败：" + r.error();
			}
			init();
		}));
	}

	private void refreshTypeButtons() {
		SearchType[] types = SearchType.values();
		for (int i = 0; i < typeButtons.size() && i < types.length; i++) {
			SearchType st = types[i];
			typeButtons.get(i).setMessage(Component.literal((st == searchType ? "● " : "") + st.label));
		}
	}

	// ---------- 弹窗（歌单选择 / 新建命名） ----------

	private int popupHeight() {
		int rows = Math.min(store.localPlaylists().size(), POPUP_MAX_ROWS);
		return 100 + rows * 28 + 30;
	}

	private int popupTop() {
		return Math.max(70, (height - popupHeight()) / 2);
	}

	/** 弹窗实际宽度（移动端窄屏自适应） */
	private int popupW() {
		return Math.min(POPUP_W, width - 16);
	}

	private void buildPopup() {
		int pw = popupW();
		int cx = width / 2;
		int px = cx - pw / 2;
		int py = popupTop();
		if (namingPlaylist) {
			int bw = pw - 24;
			nameBox = new EditBox(font, px + 12, py + 42, bw, 22, Component.literal("歌单名"));
			nameBox.setMaxLength(32);
			nameBox.setValue(pendingName);
			nameBox.setHint(Component.literal("输入歌单名称"));
			addRenderableWidget(nameBox);
			addRenderableWidget(Button.builder(Component.literal("确认创建"), b -> confirmCreate())
					.bounds(px + 12, py + 72, (bw - 8) / 2, BTN_H).build());
			addRenderableWidget(Button.builder(Component.literal("取消"), b -> {
						namingPlaylist = false;
						if (pendingSong != null) pickingPlaylist = true;
						init();
					})
					.bounds(px + 20 + (bw - 8) / 2, py + 72, (bw - 8) / 2, BTN_H).build());
			return;
		}
		int y = py + 30;
		int shown = 0;
		for (PlaylistStore.LocalPlaylist pl : store.localPlaylists()) {
			if (shown >= POPUP_MAX_ROWS) break;
			final String pid = pl.id;
			final String pname = pl.name;
			addRenderableWidget(Button.builder(Component.literal("▸ " + pname + "（" + pl.songs.size() + " 首）"),
					b -> {
						store.addToPlaylist(pid, pendingSong);
						status = "已收藏到 “" + pname + "”：" + pendingSong.name;
						pickingPlaylist = false;
						pendingSong = null;
						init();
					})
					.bounds(px + 12, y, pw - 24, BTN_H).build());
			y += 28;
			shown++;
		}
		addRenderableWidget(Button.builder(Component.literal("＋ 新建歌单…"), b -> {
					namingPlaylist = true;
					pendingName = "";
					init();
				})
				.bounds(px + 12, y, pw - 24, BTN_H).build());
	}

	private void confirmCreate() {
		String name = nameBox != null ? nameBox.getValue().trim() : pendingName.trim();
		if (name.isEmpty()) {
			status = "请输入歌单名称";
			return;
		}
		PlaylistStore.LocalPlaylist pl = store.createPlaylist(name);
		if (pendingSong != null) {
			store.addToPlaylist(pl.id, pendingSong);
			status = "已新建歌单并收藏：“" + pl.name + "”";
		} else {
			status = "已新建歌单：“" + pl.name + "”";
		}
		pickingPlaylist = false;
		namingPlaylist = false;
		pendingSong = null;
		if (tab == Tab.LOCAL) currentPlaylistId = pl.id;
		init();
	}

	// ---------- 输入（1.21.1 旧签名） ----------

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		// ESC：二级菜单返回，一级关闭
		if (keyCode == GLFW_KEY_ESCAPE) {
			if (pickingPlaylist) {
				pickingPlaylist = false;
				pendingSong = null;
				init();
				return true;
			}
			if (namingPlaylist) {
				namingPlaylist = false;
				if (pendingSong != null) pickingPlaylist = true;
				init();
				return true;
			}
			if (tab == Tab.LOCAL && currentPlaylistId != null) {
				currentPlaylistId = null;
				init();
				return true;
			}
			if (tab == Tab.SEARCH && drilling) {
				backToSearch();
				return true;
			}
			return super.keyPressed(keyCode, scanCode, modifiers); // 一级：默认关闭界面
		}
		boolean inputFocused = (inputBox != null && inputBox.isFocused())
				|| (cookieBox != null && cookieBox.isFocused())
				|| (nameBox != null && nameBox.isFocused());
		if ((keyCode == GLFW_KEY_ENTER || keyCode == GLFW_KEY_KP_ENTER) && cookieBox != null && cookieBox.isFocused()) {
			doLogin(cookieBox.getValue().trim());
			return true;
		}
		if ((keyCode == GLFW_KEY_ENTER || keyCode == GLFW_KEY_KP_ENTER) && nameBox != null && nameBox.isFocused()) {
			confirmCreate();
			return true;
		}
		if ((keyCode == GLFW_KEY_ENTER || keyCode == GLFW_KEY_KP_ENTER)
				&& inputBox != null && inputBox.isFocused()) {
			if (tab == Tab.SEARCH) {
				doSearch();
				return true;
			}
			if (tab == Tab.PLAYLIST) {
				doSync();
				return true;
			}
		}
		if (!inputFocused && !pickingPlaylist && !namingPlaylist && list != null && list.hasSongs()) {
			if (keyCode == GLFW_KEY_UP) { list.moveUp(); return true; }
			if (keyCode == GLFW_KEY_DOWN) { list.moveDown(); return true; }
			if ((keyCode == GLFW_KEY_ENTER || keyCode == GLFW_KEY_KP_ENTER) && list.hasSelection()) {
				list.activateSelected();
				return true;
			}
			if ((keyCode == GLFW_KEY_DELETE || keyCode == GLFW_KEY_BACKSPACE) && list.hasSelection()) {
				if (tab == Tab.LOCAL && currentPlaylistId != null) {
					removeSelectedFromLocal();
					return true;
				}
				if (tab == Tab.LOCAL && currentPlaylistId == null) {
					int idx = (int) list.selectedSong().id;
					List<PlaylistStore.LocalPlaylist> pls = store.localPlaylists();
					if (idx >= 0 && idx < pls.size() && !PlaylistStore.LIKE_ID.equals(pls.get(idx).id)) {
						store.removePlaylist(pls.get(idx).id);
						status = "已删除歌单：" + pls.get(idx).name;
						init();
						return true;
					}
				}
			}
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		// 弹窗打开时，滚动只作用于弹窗内，不穿透到底层歌曲列表
		if (pickingPlaylist || namingPlaylist) {
			return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
		}
		if (list != null
				&& mouseX >= list.x && mouseX < list.x + list.w
				&& mouseY >= list.y && mouseY < list.y + list.h) {
			list.mouseScrolled(verticalAmount);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	public void mouseMoved(double mouseX, double mouseY) {
		// 长按检测由 tick 驱动；这里仅保留基类行为
		super.mouseMoved(mouseX, mouseY);
	}

	@Override
	public void tick() {
		super.tick();
		// 驱动列表长按检测（触摸模拟鼠标时 drag 事件未必连续，tick 保证长按判定）
		if (list != null) list.tick(System.currentTimeMillis());
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		// 弹窗打开时，点击优先交给弹窗按钮，不穿透到下层歌曲列表（避免点歌/选中被误触发）
		if (pickingPlaylist || namingPlaylist) {
			return super.mouseClicked(mouseX, mouseY, button);
		}
		if (list != null && list.mouseClicked(mouseX, mouseY, button)) {
			return true;
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
		// 触摸拖动滚动列表（Foldcraft/Zalith 触摸拖动会模拟为按住左键拖动）
		if (list != null && list.mouseDragged(mouseX, mouseY, button)) {
			return true;
		}
		return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		// 松手触发短按点击（列表内部判定：拖动/长按后不触发）
		if (list != null && list.mouseReleased(mouseX, mouseY, button)) {
			return true;
		}
		return super.mouseReleased(mouseX, mouseY, button);
	}

	// ---------- 渲染（1.21.1：render + GuiGraphics） ----------

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.renderBackground(g, mouseX, mouseY, partialTick);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		renderBackground(g, mouseX, mouseY, partialTick);
		super.render(g, mouseX, mouseY, partialTick);

		g.fill(0, 0, width, height, 0xC0101010);
		g.drawCenteredString(font, "AllMusic 点歌", width / 2, 2, 0xFFFFFFFF);
		int cx = width / 2;
		int lx = cx - contentW() / 2;
		int cw = contentW();

		if (tab == Tab.MINE) {
			renderMine(g, lx, mouseX, mouseY);
		} else if (tab == Tab.COMMAND) {
			renderCommandPage(g, lx);
		} else {
			int listTopY = tab == Tab.SEARCH ? 64 + BTN_H + 2 : 64;
			g.drawString(font, listTitle, lx, listTopY - 12, 0xFFA0A0A0);
			if (list != null) list.render(g, popupOpen() ? -1 : mouseX, popupOpen() ? -1 : mouseY);
			if (tab == Tab.SEARCH && !hasSearched && !drilling) {
				g.drawCenteredString(font, "输入关键词后点「搜索」", width / 2, height / 2 - 4, 0xFF8F8F8F);
				g.drawCenteredString(font, "支持单曲 / 歌手 / 专辑 / 歌单 · 上下拖动列表", width / 2, height / 2 + 8, 0xFF6E6E6E);
			}
			if (tab == Tab.LOCAL && currentPlaylistId == null && store.localPlaylists().isEmpty()) {
				g.drawCenteredString(font, "还没有本地歌单，点「＋ 新建歌单」创建", width / 2, height / 2 - 4, 0xFF8F8F8F);
			}
		}
		g.drawCenteredString(font, status, width / 2, height - TAB_H - 18, 0xFF8F8F8F);

		if (pickingPlaylist || namingPlaylist) {
			drawPopup(g, mouseX, mouseY);
		}
	}

	private void drawPopup(GuiGraphics g, int mx, int my) {
		int pw = popupW();
		int cx = width / 2;
		int px = cx - pw / 2;
		int ph = popupHeight();
		int py = popupTop();
		g.fill(px, py, px + pw, py + ph, 0xF0202020);
		g.fill(px, py, px + pw, py + 1, 0xFF555555);
		g.fill(px, py + ph - 1, px + pw, py + ph, 0xFF555555);
		g.fill(px, py, px + 1, py + ph, 0xFF555555);
		g.fill(px + pw - 1, py, px + pw, py + ph, 0xFF555555);
		g.drawCenteredString(font, namingPlaylist ? "新建歌单" : "选择歌单（收藏到）", cx, py + 8, 0xFFFFFFFF);
		if (namingPlaylist && pendingSong != null) {
			g.drawString(font, "将收藏：" + pendingSong.name, px + 12, py + 24, 0xFFC0C0C0);
		}
		if (pickingPlaylist && pendingSong != null) {
			g.drawString(font, pendingSong.name, px + 12, py + 20, 0xFFC0C0C0);
		}
		g.drawString(font, "✕ 或 ESC 取消", px + pw - 84, py + ph - 14, 0xFF6E6E6E);
	}

	private void renderMine(GuiGraphics g, int lx, int mx, int my) {
		int top = mineTop();
		int bottom = mineBottom();
		int cw = contentW();
		boolean narrow = width < NARROW_W;
		int leftW = narrow ? cw : cw * 2 / 5;
		int listTop = top + BTN_H + 26;
		int listH = narrow ? Math.max(60, (bottom - listTop) * 2 / 5) : Math.max(60, bottom - listTop);

		// 账号头像与昵称（信息行，按钮在 buildMinePage 已放大置顶）
		int ax = lx;
		int aw = 48;
		g.fill(ax, top, ax + aw, top + aw, 0xFF202020);
		g.fill(ax, top, ax + aw, top + 1, 0xFF555555);
		g.fill(ax, top + aw - 1, ax + aw, top + aw, 0xFF555555);
		g.fill(ax, top, ax + 1, top + aw, 0xFF555555);
		g.fill(ax + aw - 1, top, ax + aw, top + aw, 0xFF555555);
		g.fill(ax + 18, top + 10, ax + 30, top + 22, 0xFF8A8A8A);
		g.fill(ax + 12, top + 28, ax + 36, top + 40, 0xFF8A8A8A);
		String name = isLoggedIn() ? store.nickname() : (loginMode ? "登录中…" : "未登录");
		g.drawString(font, isLoggedIn() ? "已登录" : "未登录", ax + 56, top + 2, isLoggedIn() ? 0xFF55FF55 : 0xFF8F8F8F);
		g.drawString(font, name, ax + 56, top + 14, 0xFFA0A0A0);

		if (list != null) list.render(g, popupOpen() ? -1 : mx, popupOpen() ? -1 : my);
		g.drawString(font, "本地歌单 · 喜欢", lx, listTop - 12, 0xFFA0A0A0);

		int rx = narrow ? lx : lx + leftW + 10;
		int rw = narrow ? cw : cw - leftW - 10;
		int panelTop = narrow ? listTop + listH + 8 : top;
		int panelBottom = narrow ? bottom : bottom;
		g.fill(rx, panelTop, rx + rw, panelBottom, 0x80202020);
		g.fill(rx, panelTop, rx + rw, panelTop + 1, 0xFF555555);
		g.fill(rx, panelBottom - 1, rx + rw, panelBottom, 0xFF555555);
		g.fill(rx, panelTop, rx + 1, panelBottom, 0xFF555555);
		g.fill(rx + rw - 1, panelTop, rx + rw, panelBottom, 0xFF555555);
		g.drawString(font, "设置", rx + 4, panelTop + 4, 0xFFA0A0A0);

		int y = top + 24;
		if (isLoggedIn()) {
			g.drawString(font, "账号：" + store.nickname() + "（uid " + store.uid() + "）", rx + 4, yMyCloudLabel, 0xFFC0C0C0);
		} else if (!loginMode) {
			g.drawString(font, "未登录：点「网易云登录」粘贴 MUSIC_U", rx + 4, yMyCloudLabel, 0xFF6E6E6E);
		}
		g.drawString(font, "我的云歌单", rx + 4, yMyCloudLabel - 14 < panelTop ? panelTop + 30 : yMyCloudLabel - 14, 0xFFA0A0A0);
		g.drawString(font, "同步歌单", rx + 4, ySyncedLabel, 0xFFA0A0A0);
		List<PlaylistStore.SyncedPlaylist> synced = store.syncedPlaylists();
		int sy = ySyncedListTop;
		if (synced.isEmpty()) {
			g.drawString(font, "（暂无同步歌单）", rx + 4, sy, 0xFF6E6E6E);
			sy += 14;
		}
		g.drawString(font, "配置：allmusic-gui/playlists.json", rx + 4, bottom - 12, 0xFF6E6E6E);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/**
	 * 覆盖基类的初始聚焦。
	 * 基类实现只在“上一次输入是键盘”时才聚焦，且是从当前焦点再 Tab 一格，
	 * 因此用鼠标开界面时焦点落空、用 G 键开界面时焦点会跳到页签而不是搜索框。
	 * 这里改为按页面直接指定控件：搜索/云歌单页聚焦输入框，登录与新建歌单弹窗
	 * 聚焦各自的输入框；下钻或其他页不抢焦点，把方向键/回车留给歌曲列表。
	 */
	@Override
	protected void setInitialFocus() {
		if (namingPlaylist && nameBox != null) {
			setInitialFocus(nameBox);
			return;
		}
		if (loginMode && cookieBox != null) {
			setInitialFocus(cookieBox);
			return;
		}
		if (inputBox != null && !drilling && !popupOpen()) {
			setInitialFocus(inputBox);
		}
	}
}
