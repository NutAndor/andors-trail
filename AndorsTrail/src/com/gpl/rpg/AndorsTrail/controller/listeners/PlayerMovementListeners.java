package com.gpl.rpg.AndorsTrail.controller.listeners;

import com.gpl.rpg.AndorsTrail.model.map.PredefinedMap;
import com.gpl.rpg.AndorsTrail.util.Coord;
import com.gpl.rpg.AndorsTrail.util.ListOfListeners;

public final class PlayerMovementListeners extends ListOfListeners<PlayerMovementListener> implements PlayerMovementListener {

	private final Function3<PlayerMovementListener, PredefinedMap, Coord, Coord> onPlayerMoved = new Function3<PlayerMovementListener, PredefinedMap, Coord, Coord>() {
		@Override public void call(PlayerMovementListener listener, PredefinedMap map, Coord newPosition, Coord previousPosition) { listener.onPlayerMoved(map, newPosition, previousPosition); }
	};

	private final Function2<PlayerMovementListener, PredefinedMap, Coord> onPlayerEnteredNewMap = new Function2<PlayerMovementListener, PredefinedMap, Coord>() {
		@Override public void call(PlayerMovementListener listener, PredefinedMap map, Coord p) { listener.onPlayerEnteredNewMap(map, p); }
	};

	@Override
	public void onPlayerMoved(PredefinedMap map, Coord newPosition, Coord previousPosition) {
		callAllListeners(this.onPlayerMoved, map, newPosition, previousPosition);
	}

	@Override
	public void onPlayerEnteredNewMap(PredefinedMap map, Coord p) {
		callAllListeners(this.onPlayerEnteredNewMap, map, p);
	}
}
