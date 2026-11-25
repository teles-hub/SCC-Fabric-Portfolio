package de.scc.calculator.client;

import de.scc.calculator.calc.LevelRecipeSource;
import de.scc.calculator.calc.RecipeBook;
import de.scc.calculator.calc.RecipeSource;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

import java.util.Set;

/** Client adapter delegating recipe conversion to the common Level-based scanner. */
@Environment(EnvType.CLIENT)
public final class VanillaRecipeSource implements RecipeSource {
    private LevelRecipeSource delegate = new LevelRecipeSource(null);

    @Override public String name() { return delegate.name(); }

    @Override public void begin() {
        Level level = Minecraft.getInstance().level;
        delegate = new LevelRecipeSource(level);
        delegate.begin();
    }

    @Override public int total() { return delegate.total(); }
    @Override public int done() { return delegate.done(); }
    @Override public boolean scanChunk(RecipeBook book, int budget) { return delegate.scanChunk(book, budget); }
    @Override public boolean restrict(de.scc.calculator.calc.AnalysisDelta delta) { return delegate.restrict(delta); }
    @Override public Set<String> scannedTypes() { return delegate.scannedTypes(); }

    static void addVanillaCatalysts(RecipeBook book) {
        LevelRecipeSource.addVanillaCatalysts(book);
    }
}
