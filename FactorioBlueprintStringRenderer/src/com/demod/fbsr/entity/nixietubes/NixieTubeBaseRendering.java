package com.demod.fbsr.entity.nixietubes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import com.demod.factorio.fakelua.LuaValue;
import com.demod.fbsr.Layer;
import com.demod.fbsr.WorldMap;
import com.demod.fbsr.def.ImageDef;
import com.demod.fbsr.entity.LampRendering;
import com.demod.fbsr.fp.FPSprite;
import com.demod.fbsr.map.MapEntity;
import com.demod.fbsr.map.MapPosition;
import com.demod.fbsr.map.MapRenderable;
import com.demod.fbsr.map.MapSprite;

public abstract class NixieTubeBaseRendering extends LampRendering {
	private static final char[] SYMBOLS_NUMERIC = new char[] { '1', '2', '3', '4', '5', '6', '7', '8', '9', '0' };
	private static final char[] SYMBOLS_ALPHA = new char[] { 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L',
			'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z' };

	private final boolean small;
	private final boolean alpha;

	private List<FPSprite> protoSymbols;

	public NixieTubeBaseRendering(boolean small, boolean alpha) {
		this.small = small;
		this.alpha = alpha;
	}

	@Override
	public void createRenderers(Consumer<MapRenderable> register, WorldMap map, MapEntity entity) {
		super.createRenderers(register, map, entity);

		int symbolIndex = 0;
		MapPosition checkPos = small ? entity.getPosition() : entity.getPosition().addUnit(0, 0.5);
		while (true) {
			checkPos = checkPos.addUnit(-1, 0);
			Optional<MapEntity> checkEntity = map.getNixieTube(checkPos);
			if (checkEntity.isPresent() && checkEntity.get().fromBlueprint().name.equals(entity.fromBlueprint().name)) {
				symbolIndex += small ? 2 : 1;
			} else {
				break;
			}
		}

		symbolIndex = (symbolIndex % protoSymbols.size());

		if (small) {
			protoSymbols.get(symbolIndex).defineSprites(def -> {
				MapSprite sprite = new MapSprite(def, Layer.OBJECT, entity.getPosition());
				sprite.setBounds(sprite.getBounds().scale(0.5).addUnit(-8.0 / 64.0, 12.0 / 64.0));
				register.accept(sprite);
			});
			protoSymbols.get((symbolIndex + 1) % protoSymbols.size()).defineSprites(def -> {
				MapSprite sprite = new MapSprite(def, Layer.OBJECT, entity.getPosition());
				sprite.setBounds(sprite.getBounds().scale(0.5).addUnit(12.0 / 64.0, 12.0 / 64.0));
				register.accept(sprite);
			});
		} else {
			protoSymbols.get(symbolIndex).defineSprites(def -> {
				MapSprite sprite = new MapSprite(def, Layer.OBJECT, entity.getPosition());
				sprite.setBounds(sprite.getBounds().addUnit(1.0 / 32.0, 1.0 / 32.0));
				register.accept(sprite);
			});
		}
	}

	@Override
	public void initFromPrototype() {
		super.initFromPrototype();

		protoSymbols = new ArrayList<>();
		char[] chars;
		if (alpha) {
			chars = SYMBOLS_ALPHA;
		} else {
			chars = SYMBOLS_NUMERIC;
		}
		for (char c : chars) {
			LuaValue lua = prototype.getTable().getRaw("sprite", "nixie-tube-sprite-" + c).get();
			protoSymbols.add(new FPSprite(profile, lua));
		}
	}

	@Override
	public void initAtlas(Consumer<ImageDef> register) {
		super.initAtlas(register);

		protoSymbols.forEach(fp -> fp.defineSprites(register));
	}

	// Limitation: createRenderers walks WEST from each nixie, accumulating
	// same-name neighbours into symbolIndex. Adding/removing a nixie at P
	// extends or breaks the chain for every same-name nixie east of P,
	// potentially many tiles. The 4-cardinal approximation catches only the
	// immediate east neighbour. A precise fix would walk east during populate
	// and add every same-name nixie in the chain to `affected`. Not in scope
	// for replay-analyzer (modded content).
	@Override
	public Set<MapPosition> populateWorldMap(WorldMap map, MapEntity entity) {
		Set<MapPosition> affected = super.populateWorldMap(map, entity);

		MapPosition nixiePos = small ? entity.getPosition() : entity.getPosition().addUnit(0, 0.5);
		map.setNixieTube(nixiePos, entity);

		affected.addAll(positionAndCardinals(nixiePos));
		return affected;
	}

	@Override
	public Set<MapPosition> unpopulateWorldMap(WorldMap map, MapEntity entity) {
		Set<MapPosition> affected = super.unpopulateWorldMap(map, entity);

		MapPosition nixiePos = small ? entity.getPosition() : entity.getPosition().addUnit(0, 0.5);
		map.removeNixieTube(nixiePos);

		affected.addAll(positionAndCardinals(nixiePos));
		return affected;
	}
}
