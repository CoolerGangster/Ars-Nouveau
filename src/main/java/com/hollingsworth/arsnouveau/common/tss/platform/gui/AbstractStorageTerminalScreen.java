package com.hollingsworth.arsnouveau.common.tss.platform.gui;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.hollingsworth.arsnouveau.client.ClientInfo;
import com.hollingsworth.arsnouveau.common.tss.platform.PlatformContainerScreen;
import com.hollingsworth.arsnouveau.common.tss.platform.util.IAutoFillTerminal;
import com.hollingsworth.arsnouveau.common.tss.platform.util.IDataReceiver;
import com.hollingsworth.arsnouveau.common.tss.platform.util.NumberFormatUtil;
import com.hollingsworth.arsnouveau.common.tss.platform.util.StoredItemStack;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button.OnPress;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.lwjgl.glfw.GLFW;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.hollingsworth.arsnouveau.common.tss.platform.gui.StorageTerminalMenu.SlotAction.*;
import static com.hollingsworth.arsnouveau.common.tss.platform.util.TerminalSyncManager.getItemId;

public abstract class AbstractStorageTerminalScreen<T extends StorageTerminalMenu> extends PlatformContainerScreen<T> implements IDataReceiver {
	private static final LoadingCache<StoredItemStack, List<String>> tooltipCache = CacheBuilder.newBuilder().expireAfterAccess(5, TimeUnit.SECONDS).build(new CacheLoader<StoredItemStack, List<String>>() {

		@Override
		public List<String> load(StoredItemStack key) throws Exception {
			return key.getStack().getTooltipLines(Minecraft.getInstance().player, getTooltipFlag()).stream().map(Component::getString).collect(Collectors.toList());
		}

	});
	protected Minecraft mc = Minecraft.getInstance();

	/** Amount scrolled in Creative mode inventory (0 = top, 1 = bottom) */
	protected float currentScroll;
	/** True if the scrollbar is being dragged */
	protected boolean isScrolling;
	/**
	 * True if the left mouse button was held down last time drawScreen was
	 * called.
	 */
	private boolean refreshItemList;
	protected boolean wasClicking;
	protected PlatformEditBox searchField;
	protected int slotIDUnderMouse = -1;
	protected int controllMode;
	protected int rowCount;
	protected int searchType;
	private String searchLast = "";
	protected boolean loadedSearch = false;
	private StoredItemStack.IStoredItemStackComparator comparator = new StoredItemStack.ComparatorAmount(false);
	protected static final ResourceLocation creativeInventoryTabs = new ResourceLocation("textures/gui/container/creative_inventory/tabs.png");
	protected GuiButton buttonSortingType, buttonDirection, buttonSearchType, buttonCtrlMode;
	private Comparator<StoredItemStack> sortComp;

	public AbstractStorageTerminalScreen(T screenContainer, Inventory inv, Component titleIn) {
		super(screenContainer, inv, titleIn);
		screenContainer.onPacket = this::onPacket;
	}

	protected void onPacket() {
		int s = menu.terminalData;
		controllMode = (s & 0b000_00_0_11) % ControllMode.VALUES.length;
		boolean rev = (s & 0b000_00_1_00) > 0;
		int type = (s & 0b000_11_0_00) >> 3;
		comparator = StoredItemStack.SortingTypes.VALUES[type % StoredItemStack.SortingTypes.VALUES.length].create(rev);
		searchType = (s & 0b111_00_0_00) >> 5;
		if(!searchField.isFocused() && (searchType & 1) > 0) {
			searchField.setFocus(true);
		}
		buttonSortingType.state = type;
		buttonDirection.state = rev ? 1 : 0;
		buttonSearchType.state = searchType;
		buttonCtrlMode.state = controllMode;

		if(!loadedSearch && menu.search != null) {
			loadedSearch = true;
			if((searchType & 2) > 0)
				searchField.setValue(menu.search);
		}
	}

	protected void sendUpdate() {
		CompoundTag c = new CompoundTag();
		c.putInt("d", updateData());
		CompoundTag msg = new CompoundTag();
		msg.put("c", c);
		menu.sendMessage(msg);
	}

	protected int updateData() {
		int d = 0;
		d |= (controllMode & 0b000_0_11);
		d |= ((comparator.isReversed() ? 1 : 0) << 2);
		d |= (comparator.type() << 3);
		d |= ((searchType & 0b111) << 5);
		return d;
	}

	@Override
	protected void init() {
		clearWidgets();
		inventoryLabelY = imageHeight - 92;
		super.init();
		this.searchField = new PlatformEditBox(getFont(), this.leftPos + 82, this.topPos + 6, 89, this.getFont().lineHeight, Component.translatable("narrator.ars_nouveau.search"));
		this.searchField.setMaxLength(100);
		this.searchField.setBordered(false);
		this.searchField.setVisible(true);
		this.searchField.setTextColor(16777215);
		this.searchField.setValue(searchLast);
		searchLast = "";
		addRenderableWidget(searchField);
		buttonSortingType = addRenderableWidget(makeButton(leftPos - 18, topPos + 5, 0, b -> {
			comparator = StoredItemStack.SortingTypes.VALUES[(comparator.type() + 1) % StoredItemStack.SortingTypes.VALUES.length].create(comparator.isReversed());
			buttonSortingType.state = comparator.type();
			sendUpdate();
			refreshItemList = true;
		}));
		buttonDirection = addRenderableWidget(makeButton(leftPos - 18, topPos + 5 + 18, 1, b -> {
			comparator.setReversed(!comparator.isReversed());
			buttonDirection.state = comparator.isReversed() ? 1 : 0;
			sendUpdate();
			refreshItemList = true;
		}));
		buttonSearchType = addRenderableWidget(new GuiButton(leftPos - 18, topPos + 5 + 18*2, 2, b -> {
			searchType = (searchType + 1) & ((IAutoFillTerminal.hasSync() || this instanceof CraftingTerminalScreen) ? 0b111 : 0b011);
			buttonSearchType.state = searchType;
			sendUpdate();
		}) {

			{
				this.texX = 194;
				this.texY = 30;
			}

			@Override
			public void renderButton(PoseStack st, int mouseX, int mouseY, float pt) {
				if (this.visible) {
					int x = getX();
					int y = getY();
					RenderSystem.setShader(GameRenderer::getPositionTexShader);
					RenderSystem.setShaderTexture(0, getGui());
					this.isHovered = mouseX >= x && mouseY >= y && mouseX < x + this.width && mouseY < y + this.height;
					//int i = this.getYImage(this.isHovered);
					RenderSystem.enableBlend();
					RenderSystem.defaultBlendFunc();
					RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
					this.blit(st, x, y, texX, texY + tile * 16, this.width, this.height);
					if((state & 1) > 0)this.blit(st, x+1, y+1, texX + 16, texY + tile * 16, this.width-2, this.height-2);
					if((state & 2) > 0)this.blit(st, x+1, y+1, texX + 16+14, texY + tile * 16, this.width-2, this.height-2);
					if((state & 4) > 0)this.blit(st, x+1, y+1, texX + 16+14*2, texY + tile * 16, this.width-2, this.height-2);
				}
			}
		});
		buttonCtrlMode = addRenderableWidget(makeButton(leftPos - 18, topPos + 5 + 18*3, 3, b -> {
			controllMode = (controllMode + 1) % ControllMode.VALUES.length;
			buttonCtrlMode.state = controllMode;
			sendUpdate();
		}));
		updateSearch();
	}

	protected void updateSearch() {
		String searchString = searchField.getValue();
		if (refreshItemList || !searchLast.equals(searchString)) {
			getMenu().itemListClientSorted.clear();
			boolean searchMod = false;
			String search = searchString;
			if (searchString.startsWith("@")) {
				searchMod = true;
				search = searchString.substring(1);
			}
			Pattern m = null;
			try {
				m = Pattern.compile(search.toLowerCase(), Pattern.CASE_INSENSITIVE);
			} catch (Throwable ignore) {
				try {
					m = Pattern.compile(Pattern.quote(search.toLowerCase()), Pattern.CASE_INSENSITIVE);
				} catch (Throwable __) {
					return;
				}
			}
			boolean notDone = false;
			try {
				for (int i = 0;i < getMenu().itemListClient.size();i++) {
					StoredItemStack is = getMenu().itemListClient.get(i);
					if (is != null && is.getStack() != null) {
						String dspName = searchMod ? getItemId(is.getStack().getItem()).getNamespace() : is.getStack().getHoverName().getString();
						notDone = true;
						if (m.matcher(dspName.toLowerCase()).find()) {
							addStackToClientList(is);
							notDone = false;
						}
						if (notDone) {
							for (String lp : tooltipCache.get(is)) {
								if (m.matcher(lp).find()) {
									addStackToClientList(is);
									notDone = false;
									break;
								}
							}
						}
					}
				}
			} catch (Exception e) {
			}
			Collections.sort(getMenu().itemListClientSorted, menu.noSort ? sortComp : comparator);
			if(!searchLast.equals(searchString)) {
				getMenu().scrollTo(0);
				this.currentScroll = 0;
				if ((searchType & 4) > 0) {
					IAutoFillTerminal.sync(searchString);
				}
				if ((searchType & 2) > 0) {
					CompoundTag nbt = new CompoundTag();
					nbt.putString("s", searchString);
					menu.sendMessage(nbt);
				}
				onUpdateSearch(searchString);
			} else {
				getMenu().scrollTo(this.currentScroll);
			}
			refreshItemList = false;
			this.searchLast = searchString;
		}
	}

	private void addStackToClientList(StoredItemStack is) {
		getMenu().itemListClientSorted.add(is);
	}

	public static TooltipFlag getTooltipFlag(){
		return Minecraft.getInstance().options.advancedItemTooltips ? TooltipFlag.Default.ADVANCED : TooltipFlag.Default.NORMAL;
	}

	@Override
	protected void containerTick() {
		updateSearch();
	}

	@Override
	public void render(PoseStack st, int mouseX, int mouseY, float partialTicks) {
		boolean flag = GLFW.glfwGetMouseButton(Minecraft.getInstance().getWindow().getWindow(), GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_RELEASE;
		int i = this.leftPos;
		int j = this.topPos;
		int k = i + 174;
		int l = j + 18;
		int i1 = k + 14;
		int j1 = l + rowCount * 18;

		if(hasShiftDown()) {
			if(!menu.noSort) {
				List<StoredItemStack> list = getMenu().itemListClientSorted;
				Object2IntMap<StoredItemStack> map = new Object2IntOpenHashMap<>();
				map.defaultReturnValue(Integer.MAX_VALUE);
				for (int m = 0; m < list.size(); m++) {
					map.put(list.get(m), m);
				}
				sortComp = Comparator.comparing(map::getInt);
				menu.noSort = true;
			}
		} else if(menu.noSort) {
			sortComp = null;
			menu.noSort = false;
			refreshItemList = true;
			menu.itemListClient = new ArrayList<>(menu.itemList);
		}

		if (!this.wasClicking && flag && mouseX >= k && mouseY >= l && mouseX < i1 && mouseY < j1) {
			this.isScrolling = this.needsScrollBars();
		}

		if (!flag) {
			this.isScrolling = false;
		}
		this.wasClicking = flag;

		if (this.isScrolling) {
			this.currentScroll = (mouseY - l - 7.5F) / (j1 - l - 15.0F);
			this.currentScroll = Mth.clamp(this.currentScroll, 0.0F, 1.0F);
			getMenu().scrollTo(this.currentScroll);
		}
		super.render(st, mouseX, mouseY, partialTicks);

		RenderSystem.setShader(GameRenderer::getPositionTexShader);
		RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
		RenderSystem.setShaderTexture(0, creativeInventoryTabs);
		i = k;
		j = l;
		k = j1;
		this.blit(st, i, j + (int) ((k - j - 17) * this.currentScroll), 232 + (this.needsScrollBars() ? 0 : 12), 0, 12, 15);


		if(this.menu.getCarried().isEmpty() && slotIDUnderMouse != -1) {
			SlotStorage slot = getMenu().storageSlotList.get(slotIDUnderMouse);
			if(slot.stack != null) {
				if (slot.stack.getQuantity() > 9999) {
					ClientInfo.setTooltip(Component.translatable("tooltip.ars_nouveau.amount", slot.stack.getQuantity()));
				}
				renderTooltip(st, slot.stack.getActualStack(), mouseX, mouseY);
				ClientInfo.setTooltip();
			}
		} else
			this.renderTooltip(st, mouseX, mouseY);

		if (buttonSortingType.isHoveredOrFocused()) {
			renderTooltip(st, Component.translatable("tooltip.ars_nouveau.sorting_" + buttonSortingType.state), mouseX, mouseY);
		}
		if (buttonSearchType.isHoveredOrFocused()) {
			renderTooltip(st, Component.translatable("tooltip.ars_nouveau.search_" + buttonSearchType.state, IAutoFillTerminal.getHandlerName()), mouseX, mouseY);
		}
		if (buttonCtrlMode.isHoveredOrFocused()) {
			renderComponentTooltip(st, Arrays.stream(I18n.get("tooltip.ars_nouveau.ctrlMode_" + buttonCtrlMode.state).split("\\\\")).map(Component::literal).collect(Collectors.toList()), mouseX, mouseY);
		}
	}

	@Override
	protected void renderLabels(PoseStack st, int mouseX, int mouseY) {
		super.renderLabels(st, mouseX, mouseY);
		st.pushPose();
		slotIDUnderMouse = drawSlots(st, mouseX, mouseY);
		st.popPose();
	}

	protected int drawSlots(PoseStack st, int mouseX, int mouseY) {
		StorageTerminalMenu term = getMenu();
		int slotHover = -1;
		for (int i = 0;i < term.storageSlotList.size();i++) {
			if(drawSlot(st, term.storageSlotList.get(i), mouseX, mouseY)){
				slotHover = i;
			}
		}
		return slotHover;
	}

	protected boolean drawSlot(PoseStack st, SlotStorage slot, int mouseX, int mouseY) {
		if (slot.stack != null) {
			this.setBlitOffset(100);
			this.itemRenderer.blitOffset = 100.0F;

			ItemStack stack = slot.stack.getStack().copy().split(1);
			int i = slot.xDisplayPosition, j = slot.yDisplayPosition;

			this.itemRenderer.renderAndDecorateItem(this.minecraft.player, stack, i, j, 0);
			this.itemRenderer.renderGuiItemDecorations(this.font, stack, i, j, null);

			drawStackSize(st, getFont(), slot.stack.getQuantity(), i, j);

			this.itemRenderer.blitOffset = 0.0F;
			this.setBlitOffset(0);
		}

		if (mouseX >= getGuiLeft() + slot.xDisplayPosition - 1 && mouseY >= getGuiTop() + slot.yDisplayPosition - 1 && mouseX < getGuiLeft() + slot.xDisplayPosition + 17 && mouseY < getGuiTop() + slot.yDisplayPosition + 17) {
			int l = slot.xDisplayPosition;
			int t = slot.yDisplayPosition;
			renderSlotHighlight(st, l, t, this.getBlitOffset());
			return true;
		}
		return false;
	}

	private void drawStackSize(PoseStack st, Font fr, long size, int x, int y) {
		float scaleFactor = 0.6f;
		RenderSystem.disableDepthTest();
		RenderSystem.disableBlend();
		String stackSize = NumberFormatUtil.formatNumber(size);
		st.pushPose();
		st.scale(scaleFactor, scaleFactor, scaleFactor);
		st.translate(0, 0, 450);
		float inverseScaleFactor = 1.0f / scaleFactor;
		int X = (int) (((float) x + 0 + 16.0f - fr.width(stackSize) * scaleFactor) * inverseScaleFactor);
		int Y = (int) (((float) y + 0 + 16.0f - 7.0f * scaleFactor) * inverseScaleFactor);
		fr.drawShadow(st, stackSize, X, Y, 16777215);
		st.popPose();
		RenderSystem.enableDepthTest();
	}

	protected boolean needsScrollBars() {
		return this.getMenu().itemListClientSorted.size() > rowCount * 9;
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int mouseButton) {
		if (slotIDUnderMouse > -1) {
			if (isPullOne(mouseButton)) {
				if (getMenu().getSlotByID(slotIDUnderMouse).stack != null && getMenu().getSlotByID(slotIDUnderMouse).stack.getQuantity() > 0) {
					storageSlotClick(getMenu().getSlotByID(slotIDUnderMouse).stack, PULL_ONE, isTransferOne(mouseButton));
					return true;
				}
				return true;
			} else if (pullHalf(mouseButton)) {
				if (!menu.getCarried().isEmpty()) {
					storageSlotClick(null, hasControlDown() ? GET_QUARTER : GET_HALF, false);
				} else {
					if (getMenu().getSlotByID(slotIDUnderMouse).stack != null && getMenu().getSlotByID(slotIDUnderMouse).stack.getQuantity() > 0) {
						storageSlotClick(getMenu().getSlotByID(slotIDUnderMouse).stack, hasControlDown() ? GET_QUARTER : GET_HALF, false);
						return true;
					}
				}
			} else if (pullNormal(mouseButton)) {
				if (!menu.getCarried().isEmpty()) {
					storageSlotClick(null, PULL_OR_PUSH_STACK, false);
				} else {
					if (getMenu().getSlotByID(slotIDUnderMouse).stack != null) {
						if (getMenu().getSlotByID(slotIDUnderMouse).stack.getQuantity() > 0) {
							storageSlotClick(getMenu().getSlotByID(slotIDUnderMouse).stack, hasShiftDown() ? SHIFT_PULL : StorageTerminalMenu.SlotAction.PULL_OR_PUSH_STACK, false);
							return true;
						}
					}
				}
			}
		} else if (GLFW.glfwGetKey(mc.getWindow().getWindow(), GLFW.GLFW_KEY_SPACE) != GLFW.GLFW_RELEASE) {
			storageSlotClick(null, SPACE_CLICK, false);
		} else {
			if (mouseButton == 1 && isHovering(searchField.getX() - leftPos, searchField.getY() - topPos, 89, this.getFont().lineHeight, mouseX, mouseY))
				searchField.setValue("");
			else if(this.searchField.mouseClicked(mouseX, mouseY, mouseButton))return true;
			else
				return super.mouseClicked(mouseX, mouseY, mouseButton);
		}
		return true;
	}

	protected void storageSlotClick(StoredItemStack slotStack, StorageTerminalMenu.SlotAction act, boolean mod) {
		menu.sync.sendInteract(slotStack, act, mod);
	}

	public boolean isPullOne(int mouseButton) {
		return switch (ctrlm()) {
			case AE -> mouseButton == 1 && hasShiftDown();
			case RS -> mouseButton == 2;
			case DEF -> mouseButton == 1 && !menu.getCarried().isEmpty();
		};
	}

	public boolean isTransferOne(int mouseButton) {
		return switch (ctrlm()) {
			case AE -> hasShiftDown() && hasControlDown();//not in AE
			case RS -> hasShiftDown() && mouseButton == 2;
			case DEF -> mouseButton == 1 && hasShiftDown();
		};
	}

	public boolean pullHalf(int mouseButton) {
		return switch (ctrlm()) {
			case AE -> mouseButton == 1;
			case RS -> mouseButton == 1;
			case DEF -> mouseButton == 1 && menu.getCarried().isEmpty();
		};
	}

	public boolean pullNormal(int mouseButton) {
		return switch (ctrlm()) {
			case AE, RS, DEF -> mouseButton == 0;
		};
	}

	private ControllMode ctrlm() {
		return ControllMode.VALUES[controllMode];
	}

	public Font getFont() {
		return font;
	}

	@Override
	public boolean keyPressed(int p_keyPressed_1_, int p_keyPressed_2_, int p_keyPressed_3_) {
		if (p_keyPressed_1_ == 256) {
			this.onClose();
			return true;
		}
		return this.searchField.keyPressed(p_keyPressed_1_, p_keyPressed_2_, p_keyPressed_3_) || this.searchField.canConsumeInput() || super.keyPressed(p_keyPressed_1_, p_keyPressed_2_, p_keyPressed_3_);
	}

	@Override
	public boolean charTyped(char p_charTyped_1_, int p_charTyped_2_) {
		if(searchField.charTyped(p_charTyped_1_, p_charTyped_2_))return true;
		return super.charTyped(p_charTyped_1_, p_charTyped_2_);
	}

	@Override
	public boolean mouseScrolled(double p_mouseScrolled_1_, double p_mouseScrolled_3_, double p_mouseScrolled_5_) {
		if (!this.needsScrollBars()) {
			return false;
		} else {
			int i = ((this.menu).itemListClientSorted.size() + 9 - 1) / 9 - 5;
			this.currentScroll = (float)(this.currentScroll - p_mouseScrolled_5_ / i);
			this.currentScroll = Mth.clamp(this.currentScroll, 0.0F, 1.0F);
			this.menu.scrollTo(this.currentScroll);
			return true;
		}
	}

	public abstract ResourceLocation getGui();

	@Override
	protected void renderBg(PoseStack st, float partialTicks, int mouseX, int mouseY) {
		RenderSystem.setShader(GameRenderer::getPositionTexShader);
		RenderSystem.setShaderTexture(0, getGui());
		this.blit(st, this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight);
	}

	public GuiButton makeButton(int x, int y, int tile, OnPress pressable) {
		GuiButton btn = new GuiButton(x, y, tile, pressable);
		btn.texX = 194;
		btn.texY = 30;
		btn.texture = getGui();
		return btn;
	}

	protected void onUpdateSearch(String text) {}

	@Override
	public void receive(CompoundTag tag) {
		menu.receiveClientNBTPacket(tag);
		refreshItemList = true;
	}

	private FakeSlot fakeSlotUnderMouse = new FakeSlot();

	@Override
	public Slot getSlotUnderMouse() {
		Slot s = super.getSlotUnderMouse();
		if(s != null)return s;
		if(slotIDUnderMouse > -1 && getMenu().getSlotByID(slotIDUnderMouse).stack != null) {
			fakeSlotUnderMouse.container.setItem(0, getMenu().getSlotByID(slotIDUnderMouse).stack.getStack());
			return fakeSlotUnderMouse;
		}
		return null;
	}
}
