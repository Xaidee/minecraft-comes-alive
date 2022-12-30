package net.mca.mixin.client;

import net.mca.Config;
import net.mca.MCA;
import net.mca.client.model.CommonVillagerModel;
import net.mca.entity.VillagerLike;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.PostEffectProcessor;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.util.Identifier;
import net.minecraft.util.Pair;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class MixinGameRenderer {
    @Shadow protected abstract void loadPostProcessor(Identifier id);

    @Shadow @Final private MinecraftClient client;

    @Shadow private @Nullable PostEffectProcessor postProcessor;

    @Shadow public abstract void disablePostProcessor();

    private Pair<String, Identifier> currentShader;

    @Inject(method = "tick", at = @At("TAIL"))
    public void onCameraSet(CallbackInfo ci) {
        if (MCA.areShadersAllowed()) {
            if (this.client.cameraEntity != null) {
                VillagerLike<?> villagerLike = CommonVillagerModel.getVillager(this.client.cameraEntity);
                if (villagerLike != null) {
                    if (postProcessor == null) {
                        if (currentShader != null) {
                            this.loadPostProcessor(currentShader.getRight());
                        } else {
                            Config.getInstance().shaderLocationsMap.entrySet().stream()
                                    .filter(entry -> villagerLike.getTraits().hasTrait(entry.getKey()))
                                    .filter(entry -> MCA.areShadersAllowed(entry.getKey() + "_shader"))
                                    .findFirst().ifPresent(entry -> {
                                        Identifier shaderId = new Identifier(entry.getValue());
                                        currentShader = new Pair<>(entry.getKey(), shaderId);
                                        this.loadPostProcessor(shaderId);
                                    });
                        }
                    } else if (currentShader != null) {
                        if (!villagerLike.getTraits().hasTrait(currentShader.getLeft())) {
                            disablePostProcessor();
                            this.currentShader = null;
                        }
                    }
                }
            }
        }
    }
}
