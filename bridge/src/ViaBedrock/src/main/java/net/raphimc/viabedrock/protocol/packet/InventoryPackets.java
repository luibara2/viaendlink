/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.protocol.packet;

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.StringTag;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.api.minecraft.Holder;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.remapper.PacketHandlers;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.api.type.types.version.VersionedTypes;
import com.viaversion.viaversion.libs.fastutil.ints.IntObjectPair;
import com.viaversion.viaversion.libs.mcstructs.converter.impl.v1_21_5.NbtConverter_v1_21_5;
import com.viaversion.viaversion.libs.mcstructs.core.Identifier;
import com.viaversion.viaversion.libs.mcstructs.dialog.ActionButton;
import com.viaversion.viaversion.libs.mcstructs.dialog.AfterAction;
import com.viaversion.viaversion.libs.mcstructs.dialog.Dialog;
import com.viaversion.viaversion.libs.mcstructs.dialog.Input;
import com.viaversion.viaversion.libs.mcstructs.dialog.action.CustomAllAction;
import com.viaversion.viaversion.libs.mcstructs.dialog.body.PlainMessageBody;
import com.viaversion.viaversion.libs.mcstructs.dialog.impl.MultiActionDialog;
import com.viaversion.viaversion.libs.mcstructs.dialog.impl.NoticeDialog;
import com.viaversion.viaversion.libs.mcstructs.dialog.input.BooleanInput;
import com.viaversion.viaversion.libs.mcstructs.dialog.input.NumberRangeInput;
import com.viaversion.viaversion.libs.mcstructs.dialog.input.SingleOptionInput;
import com.viaversion.viaversion.libs.mcstructs.dialog.input.TextInput;
import com.viaversion.viaversion.libs.mcstructs.dialog.serializer.DialogSerializer;
import com.viaversion.viaversion.libs.mcstructs.text.TextComponent;
import com.viaversion.viaversion.libs.mcstructs.text.components.StringComponent;
import com.viaversion.viaversion.libs.mcstructs.text.components.TranslationComponent;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ServerboundPackets26_1;
import net.lenni0451.mcstructs_bedrock.forms.Form;
import net.lenni0451.mcstructs_bedrock.forms.elements.*;
import net.lenni0451.mcstructs_bedrock.forms.serializer.FormSerializer;
import net.lenni0451.mcstructs_bedrock.forms.types.ActionForm;
import net.lenni0451.mcstructs_bedrock.forms.types.CustomForm;
import net.lenni0451.mcstructs_bedrock.forms.types.ModalForm;
import net.lenni0451.mcstructs_bedrock.text.utils.BedrockTextUtils;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.chunk.BedrockBlockEntity;
import net.raphimc.viabedrock.api.model.container.ChestContainer;
import net.raphimc.viabedrock.api.model.container.Container;
import net.raphimc.viabedrock.api.model.container.player.InventoryContainer;
import net.raphimc.viabedrock.api.model.entity.Entity;
import net.raphimc.viabedrock.api.util.PacketFactory;
import net.raphimc.viabedrock.api.util.TextUtil;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.*;
import net.raphimc.viabedrock.protocol.data.generated.bedrock.CustomBlockTags;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.ContainerInput;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.EquipmentSlot;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.model.FullContainerName;
import net.raphimc.viabedrock.protocol.rewriter.BlockStateRewriter;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;
import net.raphimc.viabedrock.protocol.storage.*;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class InventoryPackets {

    private static final int DIALOG_BUTTON_WIDTH = 200;
    private static final int DIALOG_FAKE_BUTTON_WIDTH = 300;
    private static final String DIALOG_FAKE_BUTTON_TEXT = "This is not actually a button, but has to be one because dialogs don't support adding text only elements. Clicking it has the same effect as closing the dialog.";
    /** Container ids already complained about, so a per-tick update does not become a per-tick log line. */
    private static final Set<Integer> WARNED_UNKNOWN_CONTAINER_IDS = ConcurrentHashMap.newKeySet();

    public static void register(final BedrockProtocol protocol) {
        protocol.registerClientbound(ClientboundBedrockPackets.CONTAINER_OPEN, ClientboundPackets26_1.OPEN_SCREEN, wrapper -> {
            final ChunkTracker chunkTracker = wrapper.user().get(ChunkTracker.class);
            final BlockStateRewriter blockStateRewriter = wrapper.user().get(BlockStateRewriter.class);
            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            final byte containerId = wrapper.read(Types.BYTE); // container id
            final byte rawType = wrapper.read(Types.BYTE); // type
            final ContainerType type = ContainerType.getByValue(rawType);
            if (type == null) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown ContainerType: " + rawType);
                wrapper.cancel();
                return;
            }
            final BlockPosition position = wrapper.read(BedrockTypes.BLOCK_POSITION); // position
            wrapper.read(BedrockTypes.VAR_LONG); // entity unique id

            if (inventoryTracker.isAnyScreenOpen()) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Server tried to open container while another container is open");
                PacketFactory.sendBedrockContainerClose(wrapper.user(), (byte) -1, ContainerType.NONE);
                wrapper.cancel();
                return;
            }
            final BedrockBlockEntity blockEntity = chunkTracker.getBlockEntity(position);
            TextComponent title = new TranslationComponent("container." + blockStateRewriter.tag(chunkTracker.getBlockState(position)));
            if (blockEntity != null && blockEntity.tag().get("CustomName") instanceof StringTag customNameTag) {
                title = TextUtil.stringToTextComponent(wrapper.user().get(ResourcePackStorage.class).getTexts().translate(customNameTag.getValue()));
            }

            final Container container;
            Integer javaMenuOverride = null;
            switch (type) {
                case INVENTORY -> {
                    // The answer to the announcement in InventoryTracker. The Java client already
                    // has this screen open by itself, so nothing is forwarded — the point is that
                    // the server now believes it too, and will accept requests against it.
                    ViaBedrock.getPlatform().getLogger().log(Level.INFO, "The server opened the player's own inventory (container id " + containerId + ").");
                    inventoryTracker.setCurrentContainer(new InventoryContainer(wrapper.user(), containerId, position, inventoryTracker.getInventoryContainer()));
                    wrapper.cancel();
                    return;
                }
                case CONTAINER -> {
                    // A double chest is still ContainerType.CONTAINER: Bedrock says nothing about
                    // the size here, and the paired half is only visible on the block entity. Get
                    // it wrong and the server's 54 items do not fit the 27 slots we made, setItems
                    // rejects the lot, and the chest renders empty.
                    final boolean doubleChest = blockEntity != null
                            && (blockEntity.tag().get("pairx") != null || blockEntity.tag().get("pairz") != null);
                    final String blockTag = blockStateRewriter.tag(chunkTracker.getBlockState(position));
                    if (CustomBlockTags.BARREL.equals(blockTag)) {
                        container = new ChestContainer(wrapper.user(), containerId, ContainerType.CONTAINER, title, position, 27,
                                ContainerEnumName.BarrelContainer, CustomBlockTags.BARREL);
                    } else if (CustomBlockTags.SHULKER_BOX.equals(blockTag)) {
                        container = new ChestContainer(wrapper.user(), containerId, ContainerType.CONTAINER, title, position, 27,
                                ContainerEnumName.ShulkerBoxContainer, CustomBlockTags.SHULKER_BOX);
                    } else if (doubleChest) {
                        container = new ChestContainer(wrapper.user(), containerId, ContainerType.CONTAINER, title, position, 54,
                                ContainerEnumName.LevelEntityContainer, CustomBlockTags.CHEST, CustomBlockTags.TRAPPED_CHEST);
                        javaMenuOverride = BedrockProtocol.MAPPINGS.getJavaMenuId("minecraft:generic_9x6");
                    } else {
                        container = new ChestContainer(wrapper.user(), containerId, title, position, 27);
                    }
                }
                case MINECART_CHEST, CHEST_BOAT -> container = new ChestContainer(wrapper.user(), containerId, type, title, position, 27,
                        ContainerEnumName.LevelEntityContainer);
                case HOPPER, MINECART_HOPPER -> container = new ChestContainer(wrapper.user(), containerId, type, title, position, 5,
                        ContainerEnumName.LevelEntityContainer, CustomBlockTags.HOPPER);
                case DISPENSER -> container = new ChestContainer(wrapper.user(), containerId, type, title, position, 9,
                        ContainerEnumName.LevelEntityContainer, CustomBlockTags.DISPENSER);
                case DROPPER -> container = new ChestContainer(wrapper.user(), containerId, type, title, position, 9,
                        ContainerEnumName.LevelEntityContainer, CustomBlockTags.DROPPER);
                case CRAFTER -> container = new ChestContainer(wrapper.user(), containerId, type, title, position, 9,
                        ContainerEnumName.CrafterLevelEntityContainer, CustomBlockTags.CRAFTER);
                case NONE, CAULDRON, JUKEBOX, ARMOR, HAND, HUD, DECORATED_POT -> { // Bedrock client can't open these containers
                    wrapper.cancel();
                    return;
                }
                default -> {
                    // Everything left needs more than moving items around: a crafting table, anvil,
                    // enchanting table or brewing stand has slots whose meaning the server derives
                    // from a recipe, and taking their result is a craft request carrying a recipe
                    // network id. Nothing here tracks recipes, so opening the screen would show the
                    // player a result they could never take. Refusing is the honest answer.
                    wrapper.cancel();
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Tried to open unimplemented container: " + type);
                    PacketFactory.sendBedrockContainerClose(wrapper.user(), containerId, ContainerType.NONE);
                    return;
                }
            }
            inventoryTracker.setCurrentContainer(container);

            wrapper.write(Types.VAR_INT, (int) containerId); // container id
            wrapper.write(Types.VAR_INT, javaMenuOverride != null
                    ? javaMenuOverride
                    : BedrockProtocol.MAPPINGS.getBedrockToJavaContainers().get(type)); // type
            wrapper.write(Types.TAG, TextUtil.textComponentToNbt(title)); // title
        });
        protocol.registerClientbound(ClientboundBedrockPackets.CONTAINER_CLOSE, ClientboundPackets26_1.CONTAINER_CLOSE, new PacketHandlers() {
            @Override
            protected void register() {
                map(Types.BYTE, Types.VAR_INT); // container id
                handler(wrapper -> {
                    final ContainerType containerType = ContainerType.getByValue(wrapper.read(Types.BYTE)); // type
                    final boolean serverInitiated = wrapper.read(Types.BOOLEAN); // server initiated

                    final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
                    final Container container = serverInitiated ? inventoryTracker.getCurrentContainer() : inventoryTracker.getPendingCloseContainer();
                    if (container == null) {
                        wrapper.cancel();
                        return;
                    }

                    if (serverInitiated && containerType != container.type()) {
                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Server tried to close container, but container type was not correct");
                        wrapper.cancel();
                        return;
                    }
                    inventoryTracker.setCurrentContainerClosed(serverInitiated);
                });
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.INVENTORY_CONTENT, ClientboundPackets26_1.CONTAINER_SET_CONTENT, wrapper -> {
            final ItemRewriter itemRewriter = wrapper.user().get(ItemRewriter.class);
            final int containerId = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // container id
            final BedrockItem[] items = wrapper.read(itemRewriter.newItemArrayType()); // items
            final FullContainerName containerName = wrapper.read(BedrockTypes.FULL_CONTAINER_NAME); // container name
            final BedrockItem storageItem = wrapper.read(itemRewriter.newItemType()); // storage item

            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            final Container container = inventoryTracker.getContainerClientbound((byte) containerId, containerName, storageItem);
            if (container != null && container.setItems(items)) {
                PacketFactory.writeJavaContainerSetContent(wrapper, container);
            } else {
                warnUnknownInventoryContainer(container, "INVENTORY_CONTENT", containerId);
                wrapper.cancel();
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.INVENTORY_SLOT, ClientboundPackets26_1.CONTAINER_SET_SLOT, wrapper -> {
            final ItemRewriter itemRewriter = wrapper.user().get(ItemRewriter.class);
            final int containerId = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // container id
            final int slot = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // slot
            final FullContainerName containerName = wrapper.read(BedrockTypes.OPTIONAL_FULL_CONTAINER_NAME); // container name
            final BedrockItem storageItem = wrapper.read(itemRewriter.optionalNewItemType()); // storage item
            final BedrockItem item = wrapper.read(itemRewriter.newItemType()); // item

            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            final Container container = inventoryTracker.getContainerClientbound((byte) containerId, containerName, storageItem);
            if (container != null && container.setItem(slot, item)) {
                if (container.type() == ContainerType.HUD && slot == 0) { // cursor item
                    wrapper.setPacketType(ClientboundPackets26_1.SET_CURSOR_ITEM);
                } else {
                    wrapper.write(Types.VAR_INT, (int) container.javaContainerId()); // container id
                    wrapper.write(Types.VAR_INT, inventoryTracker.nextRevision()); // revision
                    wrapper.write(Types.SHORT, (short) container.javaSlot(slot)); // slot
                }
                wrapper.write(VersionedTypes.V26_2.item, container.getJavaItem(slot)); // item
            } else {
                warnUnknownInventoryContainer(container, "INVENTORY_SLOT", containerId);
                wrapper.cancel();
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.ITEM_STACK_RESPONSE, null, wrapper -> {
            wrapper.cancel();
            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            final ItemStackRequestTracker requestTracker = wrapper.user().get(ItemStackRequestTracker.class);

            final int responseCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // response count
            for (int i = 0; i < responseCount; i++) {
                final int rawResult = wrapper.read(Types.BYTE); // result
                final ItemStackNetResult result = ItemStackNetResult.getByValue(rawResult);
                final int requestId = wrapper.read(BedrockTypes.VAR_INT); // request id
                final boolean success = result == ItemStackNetResult.Success;

                final List<ItemStackRequestTracker.SlotUpdate> updates = new ArrayList<>();
                if (success) { // Only a successful response carries containers; a failed one stops here
                    final int containerCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // container count
                    for (int c = 0; c < containerCount; c++) {
                        final FullContainerName containerName = wrapper.read(BedrockTypes.FULL_CONTAINER_NAME); // container name
                        final int slotCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // slot count
                        for (int s = 0; s < slotCount; s++) {
                            final int slot = wrapper.read(Types.UNSIGNED_BYTE); // slot
                            wrapper.read(Types.UNSIGNED_BYTE); // hotbar slot
                            final int count = wrapper.read(Types.UNSIGNED_BYTE); // count
                            final int stackNetworkId = wrapper.read(BedrockTypes.VAR_INT); // stack network id
                            wrapper.read(BedrockTypes.STRING); // custom name
                            wrapper.read(BedrockTypes.STRING); // filtered custom name
                            wrapper.read(BedrockTypes.VAR_INT); // durability correction

                            final Container.ContainerSlot resolved = inventoryTracker.resolveRequestSlot(containerName, slot);
                            if (resolved != null) {
                                updates.add(new ItemStackRequestTracker.SlotUpdate(resolved.container(), resolved.slot(), count, stackNetworkId));
                            }
                        }
                    }
                } else if (result == null) {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown ItemStackNetResult: " + rawResult);
                }

                requestTracker.handleResponse(requestId, result == null ? "unknown result " + rawResult : result.name(), success, updates);
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.MODAL_FORM_REQUEST, ClientboundPackets26_1.SHOW_DIALOG, wrapper -> {
            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            final int id = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // id
            final String data = wrapper.read(BedrockTypes.STRING); // data

            if (inventoryTracker.getCurrentContainer() != null || inventoryTracker.getCurrentForm() != null) {
                final PacketWrapper modalFormResponse = PacketWrapper.create(ServerboundBedrockPackets.MODAL_FORM_RESPONSE, wrapper.user());
                modalFormResponse.write(BedrockTypes.UNSIGNED_VAR_INT, id); // id
                modalFormResponse.write(Types.BOOLEAN, false); // has response
                modalFormResponse.write(Types.BOOLEAN, true); // has cancel reason
                modalFormResponse.write(Types.BYTE, (byte) ModalFormCancelReason.UserBusy.getValue()); // cancel reason
                modalFormResponse.sendToServer(BedrockProtocol.class);
                wrapper.cancel();
                return;
            }

            final Form form;
            try {
                form = FormSerializer.deserialize(data);
            } catch (Throwable e) { // Bedrock client shows error modal form
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error while deserializing form data: " + data, e);
                wrapper.cancel();
                return;
            }
            final ResourcePackStorage resourcePackStorage = wrapper.user().get(ResourcePackStorage.class);
            form.setTranslator(resourcePackStorage.getTexts()::translate);
            inventoryTracker.setCurrentForm(IntObjectPair.of(id, form));

            final Identifier responseIdentifier = Identifier.of("viabedrock", "form/" + id);
            final CompoundTag exitButtonAdditions = new CompoundTag();
            exitButtonAdditions.putBoolean("exit", true);
            final ActionButton exitButton = new ActionButton(new StringComponent(resourcePackStorage.getTexts().get("gui.close")), DIALOG_BUTTON_WIDTH, new CustomAllAction(responseIdentifier, exitButtonAdditions));

            final Dialog dialog;
            if (form instanceof ModalForm modalForm) {
                final MultiActionDialog actionDialog = new MultiActionDialog(TextUtil.stringToTextComponent(form.getTitle()), true, false, AfterAction.CLOSE, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), exitButton, 1);
                addTextToDialog(wrapper.user(), actionDialog, modalForm.getText());
                final CompoundTag button1Additions = new CompoundTag();
                button1Additions.putInt("button_id", 0);
                actionDialog.getActions().add(new ActionButton(TextUtil.stringToTextComponent(modalForm.getButton1()), DIALOG_BUTTON_WIDTH, new CustomAllAction(responseIdentifier, button1Additions)));
                final CompoundTag button2Additions = new CompoundTag();
                button2Additions.putInt("button_id", 1);
                actionDialog.getActions().add(new ActionButton(TextUtil.stringToTextComponent(modalForm.getButton2()), DIALOG_BUTTON_WIDTH, new CustomAllAction(responseIdentifier, button2Additions)));
                dialog = actionDialog;
            } else if (form instanceof ActionForm actionForm) {
                if (actionForm.getElements().length == 0) { // Text only form
                    final NoticeDialog noticeDialog = new NoticeDialog(TextUtil.stringToTextComponent(form.getTitle()), true, false, AfterAction.CLOSE, new ArrayList<>(), new ArrayList<>(), exitButton);
                    addTextToDialog(wrapper.user(), noticeDialog, actionForm.getText());
                    dialog = noticeDialog;
                } else {
                    final MultiActionDialog actionDialog = new MultiActionDialog(TextUtil.stringToTextComponent(form.getTitle()), true, false, AfterAction.CLOSE, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), exitButton, 1);
                    addTextToDialog(wrapper.user(), actionDialog, actionForm.getText());
                    int buttonIndex = 0;
                    for (int elementIndex = 0; elementIndex < actionForm.getElements().length; elementIndex++) {
                        final FormElement element = actionForm.getElements()[elementIndex];
                        if (element instanceof ButtonFormElement button) {
                            final CompoundTag buttonAdditions = new CompoundTag();
                            buttonAdditions.putInt("button_id", buttonIndex);
                            actionDialog.getActions().add(new ActionButton(TextUtil.stringToTextComponent(button.getText()), DIALOG_BUTTON_WIDTH, new CustomAllAction(responseIdentifier, buttonAdditions)));
                            buttonIndex++;
                        } else if (element instanceof HeaderFormElement header) {
                            actionDialog.getActions().add(new ActionButton(TextUtil.stringToTextComponent(header.getText()), new StringComponent(DIALOG_FAKE_BUTTON_TEXT), DIALOG_FAKE_BUTTON_WIDTH, exitButton.getAction()));
                        } else if (element instanceof LabelFormElement label) {
                            actionDialog.getActions().add(new ActionButton(TextUtil.stringToTextComponent(label.getText()), new StringComponent(DIALOG_FAKE_BUTTON_TEXT), DIALOG_FAKE_BUTTON_WIDTH, exitButton.getAction()));
                        } else if (element instanceof DividerFormElement) {
                        } else {
                            throw new IllegalArgumentException("Unhandled form element type: " + element.getClass().getSimpleName());
                        }
                    }
                    dialog = actionDialog;
                }
            } else if (form instanceof CustomForm customForm) {
                final MultiActionDialog actionDialog = new MultiActionDialog(TextUtil.stringToTextComponent(form.getTitle()), true, false, AfterAction.CLOSE, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), exitButton, 1);
                for (int elementIndex = 0; elementIndex < customForm.getElements().length; elementIndex++) {
                    final FormElement element = customForm.getElements()[elementIndex];
                    final String inputKey = String.valueOf(elementIndex);
                    if (element instanceof CheckboxFormElement checkbox) {
                        final BooleanInput booleanInput = new BooleanInput(TextUtil.stringToTextComponent(checkbox.getText()));
                        booleanInput.setInitial(checkbox.getDefaultValue());
                        actionDialog.getInputs().add(new Input(inputKey, booleanInput));
                    } else if (element instanceof DropdownFormElement dropdown) {
                        final SingleOptionInput singleOptionInput = new SingleOptionInput(new ArrayList<>(dropdown.getOptions().length), TextUtil.stringToTextComponent(dropdown.getText()));
                        for (int dropdownIndex = 0; dropdownIndex < dropdown.getOptions().length; dropdownIndex++) {
                            final String option = dropdown.getOptions()[dropdownIndex];
                            singleOptionInput.getOptions().add(new SingleOptionInput.Entry(String.valueOf(dropdownIndex), TextUtil.stringToTextComponent(option), dropdownIndex == dropdown.getDefaultOption()));
                        }
                        actionDialog.getInputs().add(new Input(inputKey, singleOptionInput));
                    } else if (element instanceof SliderFormElement slider) {
                        final NumberRangeInput numberRangeInput = new NumberRangeInput(TextUtil.stringToTextComponent(slider.getText()), new NumberRangeInput.Range(slider.getMin(), slider.getMax(), slider.getDefaultValue(), slider.getStep()));
                        actionDialog.getInputs().add(new Input(inputKey, numberRangeInput));
                    } else if (element instanceof StepSliderFormElement stepSlider) {
                        final SingleOptionInput singleOptionInput = new SingleOptionInput(new ArrayList<>(stepSlider.getSteps().length), TextUtil.stringToTextComponent(stepSlider.getText()));
                        for (int stepIndex = 0; stepIndex < stepSlider.getSteps().length; stepIndex++) {
                            final String step = stepSlider.getSteps()[stepIndex];
                            final String stepKey = String.valueOf(stepIndex);
                            singleOptionInput.getOptions().add(new SingleOptionInput.Entry(stepKey, TextUtil.stringToTextComponent(step), stepIndex == stepSlider.getDefaultStep()));
                        }
                        actionDialog.getInputs().add(new Input(inputKey, singleOptionInput));
                    } else if (element instanceof TextFieldFormElement textField) {
                        final TextInput textInput = new TextInput(TextUtil.stringToTextComponent(textField.getText()));
                        textInput.setMaxLength(100);
                        textInput.setInitial(textField.getDefaultValue());
                        actionDialog.getInputs().add(new Input(inputKey, textInput));
                    } else if (element instanceof HeaderFormElement header) {
                        addTextToDialog(wrapper.user(), actionDialog, header.getText());
                    } else if (element instanceof LabelFormElement label) {
                        addTextToDialog(wrapper.user(), actionDialog, label.getText());
                    } else if (element instanceof DividerFormElement) {
                        if (wrapper.user().getProtocolInfo().protocolVersion().newerThanOrEqualTo(ProtocolVersion.v1_21_6)) {
                            final TextInput textInput = new TextInput(new StringComponent());
                            textInput.setLabelVisible(false);
                            textInput.setMaxLength(Integer.MAX_VALUE);
                            textInput.setMultiline(new TextInput.MultilineOptions(null, 1));
                            actionDialog.getInputs().add(new Input("dummy", textInput));
                        }
                    } else {
                        throw new IllegalArgumentException("Unhandled form element type: " + element.getClass().getSimpleName());
                    }
                }
                actionDialog.getActions().add(new ActionButton(TextUtil.stringToTextComponent(resourcePackStorage.getTexts().get("gui.submit")), DIALOG_BUTTON_WIDTH, new CustomAllAction(responseIdentifier, null)));
                dialog = actionDialog;
            } else {
                throw new IllegalArgumentException("Unhandled form type: " + form.getClass().getSimpleName());
            }

            wrapper.write(Types.TRUSTED_COMPOUND_TAG_HOLDER, Holder.of((CompoundTag) DialogSerializer.V1_21_6.getDirectCodec().serialize(NbtConverter_v1_21_5.INSTANCE, dialog).get())); // dialog data
        });
        protocol.registerClientbound(ClientboundBedrockPackets.CLOSE_FORM, ClientboundPackets26_1.CLEAR_DIALOG, wrapper -> {
            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            if (inventoryTracker.getCurrentForm() != null) {
                inventoryTracker.closeCurrentForm();
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.PLAYER_HOTBAR, ClientboundPackets26_1.SET_HELD_SLOT, wrapper -> {
            final InventoryContainer inventoryContainer = wrapper.user().get(InventoryTracker.class).getInventoryContainer();
            final int slot = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // selected slot
            final byte containerId = wrapper.read(Types.BYTE); // container id
            final boolean shouldSelectSlot = wrapper.read(Types.BOOLEAN); // should select slot
            if (slot >= 0 && slot < 9 && containerId == inventoryContainer.containerId() && shouldSelectSlot) {
                wrapper.write(Types.VAR_INT, slot); // slot
            } else {
                wrapper.cancel();
                if (containerId != inventoryContainer.containerId()) { // Bedrock client doesn't render hotbar selection and held item anymore
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Tried to set hotbar slot with wrong container id: " + containerId);
                }
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.CONTAINER_REGISTRY_CLEANUP, null, wrapper -> {
            wrapper.cancel();
            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            final FullContainerName[] removedContainers = wrapper.read(BedrockTypes.FULL_CONTAINER_NAME_ARRAY); // removed containers
            for (FullContainerName containerName : removedContainers) {
                inventoryTracker.removeDynamicContainer(containerName);
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.PLAYER_ARMOR_DAMAGE, ClientboundPackets26_1.SET_EQUIPMENT, wrapper -> {
            if (!wrapper.user().get(GameSessionStorage.class).isInventoryServerAuthoritative()) {
                wrapper.cancel();
                return;
            }
            final int size = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // size
            if (size <= 0) {
                wrapper.cancel();
                return;
            }
            final Container armorContainer = wrapper.user().get(InventoryTracker.class).getArmorContainer();

            wrapper.write(Types.VAR_INT, wrapper.user().get(EntityTracker.class).getClientPlayer().javaId()); // entity id
            for (int i = 0; i < size; i++) {
                final int rawArmorSlot = wrapper.read(BedrockTypes.VAR_INT); // armor slot
                final SharedTypes_Legacy_ArmorSlot armorSlot = SharedTypes_Legacy_ArmorSlot.getByValue(rawArmorSlot);
                if (armorSlot == null) { // Bedrock client ignores the whole packet if an unknown armor slot is sent
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown SharedTypes_Legacy_ArmorSlot: " + rawArmorSlot);
                    wrapper.cancel();
                    return;
                }
                final short damage = wrapper.read(BedrockTypes.SHORT_LE); // damage

                final BedrockItem item = armorSlot.getValue() < armorContainer.size() ? armorContainer.getItem(armorSlot.getValue()) : BedrockItem.empty();
                if (item.tag() == null) {
                    item.setTag(new CompoundTag());
                }
                item.tag().putInt("Damage", damage);

                final EquipmentSlot equipmentSlot = switch (armorSlot) {
                    case Head -> EquipmentSlot.HEAD;
                    case Torso -> EquipmentSlot.CHEST;
                    case Legs -> EquipmentSlot.LEGS;
                    case Feet -> EquipmentSlot.FEET;
                    case Body -> EquipmentSlot.BODY;
                };
                wrapper.write(Types.BYTE, (byte) (equipmentSlot.ordinal() | (i < (size - 1) ? Byte.MIN_VALUE : 0))); // slot
                wrapper.write(VersionedTypes.V26_2.item, wrapper.user().get(ItemRewriter.class).javaItem(item)); // item
            }
        });

        protocol.registerServerbound(ServerboundPackets26_1.CONTAINER_CLICK, null, wrapper -> {
            wrapper.cancel();
            final int containerId = wrapper.read(Types.VAR_INT); // container id
            final int revision = wrapper.read(Types.VAR_INT); // revision
            final short slot = wrapper.read(Types.SHORT); // slot
            final byte button = wrapper.read(Types.BYTE); // button
            final ContainerInput action = ContainerInput.values()[wrapper.read(Types.VAR_INT)]; // action

            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            if (inventoryTracker.getPendingCloseContainer() != null) {
                wrapper.cancel();
                return;
            }
            Container container = inventoryTracker.getContainerServerbound((byte) containerId);
            if (container == null) {
                if (containerId != ContainerID.CONTAINER_ID_INVENTORY.getValue()) {
                    wrapper.cancel();
                    return;
                }
                // A Java client opens its own inventory without telling anyone — there is no packet
                // for it — so the first the server hears of it is this click. A Bedrock client would
                // have announced the screen when it opened, and the server will not accept item
                // stack requests against the inventory until it has: without this the click is
                // refused and the item snaps back, which is why moving items inside the inventory
                // failed while moving them into an open chest worked.
                //
                // The click is held rather than sent alongside, because the announcement has to
                // arrive first. It is replayed the moment the server confirms the screen is open —
                // or after a timeout, if this server never does. Announcing repeatedly is fine: a
                // Bedrock client does the same when the server does not respond.
                if (inventoryTracker.deferClickUntilInventoryOpens(revision, slot, button, action)) {
                    wrapper.cancel();
                    return;
                }
                // Still waiting on an earlier announcement. Handle it anyway rather than swallow it:
                // a refused request is rolled back, a swallowed click is just gone.
                container = inventoryTracker.getInventoryContainer();
            }
            if (!container.handleClick(revision, slot, button, action)) {
                if (container.type() != ContainerType.INVENTORY) {
                    PacketFactory.sendJavaContainerSetContent(wrapper.user(), inventoryTracker.getInventoryContainer());
                }
                PacketFactory.sendJavaContainerSetContent(wrapper.user(), container);
            }
        });
        protocol.registerServerbound(ServerboundPackets26_1.SET_CREATIVE_MODE_SLOT, null, wrapper -> {
            wrapper.cancel();
            final short slot = wrapper.read(Types.SHORT); // slot
            final Item item = wrapper.read(VersionedTypes.V26_2.lengthPrefixedItem); // item

            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            if (inventoryTracker.getPendingCloseContainer() != null) {
                wrapper.cancel();
                return;
            }
            PacketFactory.sendJavaContainerSetContent(wrapper.user(), inventoryTracker.getInventoryContainer());
        });
        protocol.registerServerbound(ServerboundPackets26_1.CUSTOM_CLICK_ACTION, ServerboundBedrockPackets.MODAL_FORM_RESPONSE, wrapper -> {
            final String id = wrapper.read(Types.STRING); // id
            final CompoundTag payload = (CompoundTag) wrapper.read(Types.CUSTOM_CLICK_ACTION_TAG); // payload
            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            if (inventoryTracker.getCurrentForm() == null) {
                wrapper.cancel();
                return;
            }

            final Form form = inventoryTracker.getCurrentForm().right();
            final int formId = inventoryTracker.getCurrentForm().leftInt();
            if (!id.equals("viabedrock:form/" + formId)) {
                wrapper.cancel();
                return;
            }

            inventoryTracker.setCurrentForm(null);
            if (payload.contains("exit") && payload.getBoolean("exit")) {
                wrapper.write(BedrockTypes.UNSIGNED_VAR_INT, formId); // id
                wrapper.write(Types.BOOLEAN, false); // has response
                wrapper.write(Types.BOOLEAN, true); // has cancel reason
                wrapper.write(Types.BYTE, (byte) ModalFormCancelReason.UserClosed.getValue()); // cancel reason
                return;
            }

            if (form instanceof ModalForm modalForm) {
                modalForm.setClickedButton(payload.getInt("button_id"));
            } else if (form instanceof ActionForm actionForm) {
                actionForm.setClickedButton(payload.getInt("button_id"));
            } else if (form instanceof CustomForm customForm) {
                for (int elementIndex = 0; elementIndex < customForm.getElements().length; elementIndex++) {
                    final String inputKey = String.valueOf(elementIndex);
                    if (!payload.contains(inputKey)) continue;
                    final FormElement element = customForm.getElements()[elementIndex];
                    if (element instanceof CheckboxFormElement checkbox) {
                        checkbox.setChecked(payload.getBoolean(inputKey));
                    } else if (element instanceof DropdownFormElement dropdown) {
                        dropdown.setSelected(Integer.parseInt(payload.getString(inputKey)));
                    } else if (element instanceof SliderFormElement slider) {
                        slider.setCurrent(payload.getFloat(inputKey));
                    } else if (element instanceof StepSliderFormElement stepSlider) {
                        stepSlider.setSelected(Integer.parseInt(payload.getString(inputKey)));
                    } else if (element instanceof TextFieldFormElement textField) {
                        textField.setValue(payload.getString(inputKey));
                    }
                }
            } else {
                throw new IllegalArgumentException("Unhandled form type: " + form.getClass().getSimpleName());
            }

            wrapper.write(BedrockTypes.UNSIGNED_VAR_INT, formId); // id
            wrapper.write(Types.BOOLEAN, true); // has response
            wrapper.write(BedrockTypes.STRING, form.serializeResponse() + '\n'); // response
            wrapper.write(Types.BOOLEAN, false); // has cancel reason
        });
        protocol.registerServerbound(ServerboundPackets26_1.CONTAINER_CLOSE, ServerboundBedrockPackets.CONTAINER_CLOSE, new PacketHandlers() {
            @Override
            protected void register() {
                map(Types.VAR_INT, Types.BYTE); // container id
                create(Types.BYTE, (byte) ContainerType.NONE.getValue()); // type
                create(Types.BOOLEAN, false); // server initiated
                handler(wrapper -> {
                    final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
                    final byte containerId = wrapper.get(Types.BYTE, 0);
                    final Container container = inventoryTracker.getContainerServerbound(containerId);
                    if (container == null) {
                        wrapper.cancel();
                        return;
                    }

                    if (container.javaContainerId() != container.containerId()) {
                        wrapper.set(Types.BYTE, 0, container.containerId());
                    }
                    inventoryTracker.markPendingClose(container);
                });
            }
        });
        protocol.registerServerbound(ServerboundPackets26_1.SET_CARRIED_ITEM, ServerboundBedrockPackets.MOB_EQUIPMENT, wrapper -> {
            final short slot = wrapper.read(Types.SHORT); // slot
            wrapper.user().get(InventoryTracker.class).getInventoryContainer().setSelectedHotbarSlot((byte) slot, wrapper); // slot
        });
        protocol.registerServerbound(ServerboundPackets26_1.PICK_ITEM_FROM_BLOCK, ServerboundBedrockPackets.BLOCK_PICK_REQUEST, wrapper -> {
            wrapper.passthroughAndMap(Types.BLOCK_POSITION1_14, BedrockTypes.BLOCK_POSITION); // position
            wrapper.passthrough(Types.BOOLEAN); // include data
            wrapper.write(Types.UNSIGNED_BYTE, (short) 9); // number of empty hotbar slots (vanilla client always sends 9)
        });
        protocol.registerServerbound(ServerboundPackets26_1.PICK_ITEM_FROM_ENTITY, ServerboundBedrockPackets.ENTITY_PICK_REQUEST, wrapper -> {
            final int entityId = wrapper.read(Types.VAR_INT); // entity id
            final boolean includeData = wrapper.read(Types.BOOLEAN); // include data

            final Entity entity = wrapper.user().get(EntityTracker.class).getEntityByJid(entityId);
            if (entity == null) {
                wrapper.cancel();
                return;
            }

            wrapper.write(BedrockTypes.LONG_LE, entity.uniqueId()); // entity unique id
            wrapper.write(Types.UNSIGNED_BYTE, (short) 9); // number of empty hotbar slots (vanilla client always sends 9)
            wrapper.write(Types.BOOLEAN, includeData); // include data
        });
    }

    /**
     * Names a container update that was thrown away, the first time each container id does it.
     *
     * <p>These used to vanish in silence, and a lost update is not a quiet failure — it is the
     * player's inventory disagreeing with the server until something forces a resync. If items
     * appear late or not at all, this is the line that says which container the server was talking
     * about and that nothing here knew what to do with it.</p>
     */
    private static void warnUnknownInventoryContainer(final Container container, final String packet, final int containerId) {
        if (container != null) {
            return; // Found, but it refused the update itself -- that is a decision, not a gap
        }
        if (WARNED_UNKNOWN_CONTAINER_IDS.add(containerId)) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Dropped " + packet
                    + " for unknown container id " + containerId + ". Items in it will not reach the client.");
        }
    }

    private static void addTextToDialog(final UserConnection userConnection, final Dialog dialog, final String text) {
        if (dialog.getInputs().isEmpty()) {
            for (String line : BedrockTextUtils.split(text, "\n")) {
                dialog.getBody().add(new PlainMessageBody(TextUtil.stringToTextComponent(line)));
            }
        } else {
            if (userConnection.getProtocolInfo().protocolVersion().newerThanOrEqualTo(ProtocolVersion.v1_21_6)) {
                for (String line : BedrockTextUtils.split(text, "\n")) {
                    final TextInput textInput = new TextInput(TextUtil.stringToTextComponent(line));
                    textInput.setMaxLength(Integer.MAX_VALUE);
                    textInput.setMultiline(new TextInput.MultilineOptions(null, 1));
                    dialog.getInputs().add(new Input("dummy", textInput));
                }
            } else { // VB compatibility
                dialog.getInputs().add(new Input("dummy", new BooleanInput(TextUtil.stringToTextComponent(text))));
            }
        }
    }

}
