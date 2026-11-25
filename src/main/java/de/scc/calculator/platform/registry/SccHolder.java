package de.scc.calculator.platform.registry;

import com.mojang.datafixers.util.Either;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;

import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Substituto do DeferredHolder do NeoForge que e tambem um Holder<T> (as 139 referencias podem ser passadas
 * direto a metodos vanilla). CORRECAO: acrescentada a fabrica register(...) que as Partes 3/PlatformRegistry
 * usam (a errata so tinha bind()), e removido o asHolder(): use "this" ou reference().
 * ATENCAO: Holder.Reference usa igualdade por identidade. Se algum codigo vanilla comparar holders por equals
 * (HolderSet.Direct, mapas), passe holder.reference() em vez do SccHolder.
 */
public class SccHolder<T> implements Holder<T>, Supplier<T> {
    private final ResourceLocation id;
    private Holder.Reference<T> reference;

    public SccHolder(ResourceLocation id) {
        this.id = id;
    }

    /** Registra no registro vanilla e devolve o holder pronto. Use em TODO registro. */
    @SuppressWarnings("unchecked")
    public static <R, T extends R> SccHolder<T> register(Registry<R> registry, ResourceLocation id, T value) {
        SccHolder<T> holder = new SccHolder<>(id);
        holder.reference = (Holder.Reference<T>) (Holder.Reference<?>) Registry.registerForHolder(registry, id, value);
        return holder;
    }

    public void bind(Registry<T> registry, T value) {
        this.reference = Registry.registerForHolder(registry, this.id, value);
    }

    public ResourceLocation getId() {
        return this.id;
    }

    public Holder.Reference<T> reference() {
        if (this.reference == null) {
            throw new IllegalStateException("SccHolder ainda nao registrado: " + this.id);
        }
        return this.reference;
    }

    @Override
    public T get() {
        return value();
    }

    @Override
    public T value() {
        return reference().value();
    }

    @Override
    public boolean isBound() {
        return this.reference != null && this.reference.isBound();
    }

    @Override
    public boolean is(ResourceLocation location) {
        return this.id.equals(location);
    }

    @Override
    public boolean is(ResourceKey<T> key) {
        return key.location().equals(this.id);
    }

    @Override
    public boolean is(Predicate<ResourceKey<T>> predicate) {
        return unwrapKey().map(predicate::test).orElse(false);
    }

    @Override
    public boolean is(TagKey<T> tagKey) {
        return this.reference != null && this.reference.is(tagKey);
    }

    // Sem @Override de proposito: se Holder.is(Holder) for default na 1.21.1 isto apenas o substitui;
    // se nao existir, continua compilando.
    public boolean is(Holder<T> holder) {
        return this.reference != null && this.reference.is(holder);
    }

    @Override
    public Stream<TagKey<T>> tags() {
        return this.reference != null ? this.reference.tags() : Stream.empty();
    }

    @Override
    public Either<ResourceKey<T>, T> unwrap() {
        return reference().unwrap();
    }

    @Override
    public Optional<ResourceKey<T>> unwrapKey() {
        return this.reference != null ? this.reference.unwrapKey() : Optional.empty();
    }

    @Override
    public Kind kind() {
        return Kind.REFERENCE;
    }

    @Override
    public boolean canSerializeIn(HolderOwner<T> owner) {
        return this.reference != null && this.reference.canSerializeIn(owner);
    }
}
