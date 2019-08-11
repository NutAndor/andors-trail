package com.gpl.rpg.AndorsTrail.model.map;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;

import android.content.res.Resources;

import com.gpl.rpg.AndorsTrail.AndorsTrailApplication;
import com.gpl.rpg.AndorsTrail.model.actor.MonsterType;
import com.gpl.rpg.AndorsTrail.model.actor.MonsterTypeCollection;
import com.gpl.rpg.AndorsTrail.model.item.DropList;
import com.gpl.rpg.AndorsTrail.model.item.DropListCollection;
import com.gpl.rpg.AndorsTrail.model.map.TMXMapFileParser.TMXLayer;
import com.gpl.rpg.AndorsTrail.model.map.TMXMapFileParser.TMXLayerMap;
import com.gpl.rpg.AndorsTrail.model.map.TMXMapFileParser.TMXMap;
import com.gpl.rpg.AndorsTrail.model.map.TMXMapFileParser.TMXObject;
import com.gpl.rpg.AndorsTrail.model.map.TMXMapFileParser.TMXObjectGroup;
import com.gpl.rpg.AndorsTrail.model.map.TMXMapFileParser.TMXObjectMap;
import com.gpl.rpg.AndorsTrail.model.map.TMXMapFileParser.TMXProperty;
import com.gpl.rpg.AndorsTrail.model.map.TMXMapFileParser.TMXTileSet;
import com.gpl.rpg.AndorsTrail.model.quest.QuestProgress;
import com.gpl.rpg.AndorsTrail.model.script.Requirement;
import com.gpl.rpg.AndorsTrail.resource.parsers.ResourceParserUtils;
import com.gpl.rpg.AndorsTrail.resource.tiles.TileCache;
import com.gpl.rpg.AndorsTrail.util.ConstRange;
import com.gpl.rpg.AndorsTrail.util.Coord;
import com.gpl.rpg.AndorsTrail.util.CoordRect;
import com.gpl.rpg.AndorsTrail.util.L;
import com.gpl.rpg.AndorsTrail.util.Range;
import com.gpl.rpg.AndorsTrail.util.Size;

public final class TMXMapTranslator {
	private final ArrayList<TMXObjectMap> maps = new ArrayList<TMXObjectMap>();

	public void read(Resources r, int xmlResourceId, String name) {
		maps.add(TMXMapFileParser.readObjectMap(r, xmlResourceId, name));
	}

	public static LayeredTileMap readLayeredTileMap(Resources res, TileCache tileCache, PredefinedMap map) {
		TMXLayerMap resultMap = TMXMapFileParser.readLayerMap(res, map.xmlResourceId, map.name);
		return transformMap(resultMap, tileCache);
	}

	public ArrayList<PredefinedMap> transformMaps(MonsterTypeCollection monsterTypes, DropListCollection dropLists) {
		return transformMaps(maps, monsterTypes, dropLists);
	}
	public ArrayList<PredefinedMap> transformMaps(Collection<TMXObjectMap> maps, MonsterTypeCollection monsterTypes, DropListCollection dropLists) {
		ArrayList<PredefinedMap> result = new ArrayList<PredefinedMap>();

		for (TMXObjectMap m : maps) {
			assert(m.name != null);
			assert(m.name.length() > 0);
			assert(m.width > 0);
			assert(m.height > 0);

			boolean isOutdoors = false;
			String colorFilter = null;
			for (TMXProperty p : m.properties) {
				if(p.name.equalsIgnoreCase("outdoors")) isOutdoors = (Integer.parseInt(p.value) != 0);
				else if(p.name.equalsIgnoreCase("colorfilter")) colorFilter = p.value;
				else if(AndorsTrailApplication.DEVELOPMENT_VALIDATEDATA) L.log("OPTIMIZE: Map " + m.name + " has unrecognized property \"" + p.name + "\".");
			}

			final Size mapSize = new Size(m.width, m.height);
			List<MapObject> mapObjects = new LinkedList<MapObject>();
			List<MonsterSpawnArea> spawnAreas = new LinkedList<MonsterSpawnArea>();
			List<String> activeGroups = new LinkedList<String>();

			for (TMXObjectGroup group : m.objectGroups) {
				boolean active = true;
				for (TMXProperty p : group.properties) {
					if (p.name.equalsIgnoreCase("active")) {
						active = Boolean.parseBoolean(p.value);
					} else if (AndorsTrailApplication.DEVELOPMENT_VALIDATEDATA) {
						L.log("OPTIMIZE: Map " + m.name + ", group " + group.name + " has unrecognized property \"" + p.name + "\".");
					}
				}
				if (active) {
					activeGroups.add(group.name);
				}
				for (TMXObject object : group.objects) {
					final CoordRect position = getTMXObjectPosition(object, m);
					final Coord topLeft = position.topLeft;

					if (object.type == null) {
						if (AndorsTrailApplication.DEVELOPMENT_VALIDATEDATA)
							L.log("WARNING: Map " + m.name + ", object \"" + object.name + "\"@" + topLeft.toString() + " has null type.");
					} else if (object.type.equalsIgnoreCase("sign")) {
						String phraseID = object.name;
						for (TMXProperty p : object.properties) {
							if (AndorsTrailApplication.DEVELOPMENT_VALIDATEDATA) L.log("OPTIMIZE: Map " + m.name + ", sign " + object.name + "@" + topLeft.toString() + " has unrecognized property \"" + p.name + "\".");
						}
						mapObjects.add(MapObject.createMapSignEvent(position, phraseID, group.name));
					} else if (object.type.equalsIgnoreCase("mapchange")) {
						String map = null;
						String place = null;
						for (TMXProperty p : object.properties) {
							if (p.name.equalsIgnoreCase("map")) {
								map = p.value;
							} else if (p.name.equalsIgnoreCase("place")) {
								place = p.value;
							} else if (AndorsTrailApplication.DEVELOPMENT_VALIDATEDATA) {
								L.log("OPTIMIZE: Map " + m.name + ", mapchange " + object.name + "@" + topLeft.toString() + " has unrecognized property \"" + p.name + "\".");
							}
						}
						mapObjects.add(MapObject.createMapChangeArea(position, object.name, map, place, group.name));
					} else if (object.type.equalsIgnoreCase("spawn")) {
						boolean isActiveForNewGame = true;
						boolean ignoreAreas = false;
						int maxQuantity = 1;
						int spawnChance = 10;
						String spawnGroup = object.name;
						for (TMXProperty p : object.properties) {
							if (AndorsTrailApplication.DEVELOPMENT_VALIDATEDATA) {
								if (p.value.equals("")) {
									L.log("OPTIMIZE: Map " + m.name + ", spawn " + object.name + "@" + topLeft.toString() + " has property \"" + p.name + "\" without value.");
									continue;
								}
							}
							if (p.name.equalsIgnoreCase("quantity")) {
								maxQuantity = Integer.parseInt(p.value);
							} else if (p.name.equalsIgnoreCase("spawnchance")) {
								spawnChance = Integer.parseInt(p.value);
							} else if (p.name.equalsIgnoreCase("active")) {
								isActiveForNewGame = Boolean.parseBoolean(p.value);
							} else if (p.name.equalsIgnoreCase("ignoreAreas")) {
								ignoreAreas = Boolean.parseBoolean(p.value);
							} else if (p.name.equalsIgnoreCase("spawngroup")) {
								spawnGroup = p.value;
							} else if (AndorsTrailApplication.DEVELOPMENT_VALIDATEDATA) {
								L.log("OPTIMIZE: Map " + m.name + ", spawn " + object.name + "@" + topLeft.toString() + " has unrecognized property \"" + p.name + "\".");
							}
						}

						ArrayList<MonsterType> types = monsterTypes.getMonsterTypesFromSpawnGroup(spawnGroup);
						if (types.isEmpty()) {
							if (AndorsTrailApplication.DEVELOPMENT_VALIDATEDATA) {
								L.log("OPTIMIZE: Map " + m.name + " contains spawn \"" + object.name + "\"@" + topLeft.toString() + " that does not correspond to any monsters. The spawn will be removed.");
							}
							continue;
						}

						String[] monsterTypeIDs = new String[types.size()];
						boolean isUnique = types.get(0).isUnique;
						for (int i = 0; i < monsterTypeIDs.length; ++i) {
							monsterTypeIDs[i] = types.get(i).id;
						}
						MonsterSpawnArea area = new MonsterSpawnArea(
								position
								,new Range(maxQuantity, 0)
								,new Range(1000, spawnChance)
								,object.name
								,monsterTypeIDs
								,isUnique
								,ignoreAreas
								,group.name
								,isActiveForNewGame
						);
						spawnAreas.add(area);
					} else if (object.type.equalsIgnoreCase("key")) {
						String phraseID = "";
						for (TMXProperty p : object.properties) {
							if (p.name.equalsIgnoreCase("phrase")) {
								phraseID = p.value;
							} else if (AndorsTrailApplication.DEVELOPMENT_VALIDATEDATA) {
								if (!requirementPropertiesNames.contains(p.name.toLowerCase())) {
									L.log("OPTIMIZE: Map " + m.name + ", key " + object.name + "@" + topLeft.toString() + " has unrecognized property \"" + p.name + "\".");
								}
							}
						}
						Requirement req = parseRequirement(object);
						mapObjects.add(MapObject.createKeyArea(position, phraseID, req, group.name));
					} else if (object.type.equals("rest")) {
						mapObjects.add(MapObject.createRestArea(position, object.name, group.name));
					} else if (object.type.equals("container")) {
						DropList dropList = dropLists.getDropList(object.name);
						if (dropList == null) continue;
						mapObjects.add(MapObject.createContainerArea(position, dropList, group.name));
					} else if (object.type.equals("replace")) {
						// Do nothing. Will be handled when reading map layers instead.
					} else if (object.type.equalsIgnoreCase("script")) {
						String phraseID = object.name;
						MapObject.MapObjectEvaluationType evaluateWhen = MapObject.MapObjectEvaluationType.whenEntering;
						for (TMXProperty p : object.properties) {
							if (p.name.equalsIgnoreCase("when")) {
								if (p.value.equalsIgnoreCase("enter")) {
									evaluateWhen = MapObject.MapObjectEvaluationType.whenEntering;
								} else if (p.value.equalsIgnoreCase("step")) {
									evaluateWhen = MapObject.MapObjectEvaluationType.onEveryStep;
								} else if (p.value.equalsIgnoreCase("round")) {
									evaluateWhen = MapObject.MapObjectEvaluationType.afterEveryRound;
								} else if (p.value.equalsIgnoreCase("always")) {
									evaluateWhen = MapObject.MapObjectEvaluationType.continuously;
								} else if (AndorsTrailApplication.DEVELOPMENT_VALIDATEDATA) {
									L.log("OPTIMIZE: Map " + m.name + ", script " + object.name + "@" + topLeft.toString() + " has unrecognized value for \"when\" property: \"" + p.value + "\".");
								}
							} else if (AndorsTrailApplication.DEVELOPMENT_VALIDATEDATA) {
								L.log("OPTIMIZE: Map " + m.name + ", script " + object.name + "@" + topLeft.toString() + " has unrecognized property \"" + p.name + "\".");
							}
						}
						mapObjects.add(MapObject.createScriptArea(position, phraseID, evaluateWhen, group.name));
					} else if (AndorsTrailApplication.DEVELOPMENT_VALIDATEDATA) {
						L.log("OPTIMIZE: Map " + m.name + ", has unrecognized object type \"" + object.type + "\" for name \"" + object.name + "\".");
					}
				}
			}
			MapObject[] _eventObjects = new MapObject[mapObjects.size()];
			_eventObjects = mapObjects.toArray(_eventObjects);
			MonsterSpawnArea[] _spawnAreas = new MonsterSpawnArea[spawnAreas.size()];
			_spawnAreas = spawnAreas.toArray(_spawnAreas);

			result.add(new PredefinedMap(m.xmlResourceId, m.name, mapSize, _eventObjects, _spawnAreas, activeGroups, isOutdoors, colorFilter));
		}

		return result;
	}
	
	private static final List<String> requirementPropertiesNames = Arrays.asList(new String[]{"requireType".toLowerCase(), "requireId".toLowerCase(), "requireValue".toLowerCase(), "requireNegation".toLowerCase()});
	
	private static Requirement parseRequirement(TMXObject object) {
		Requirement.RequirementType requireType = Requirement.RequirementType.questProgress;
		String requireId = null;
		int requireValue = 0;
		boolean requireNegation = false;
		ConstRange requireChance = null;
		for (TMXProperty p : object.properties) {
			if (p.name.equalsIgnoreCase("requireType")) {
				try {
					requireType = Requirement.RequirementType.valueOf(p.value);
				} catch (IllegalArgumentException e) {
					requireType = null;
					if (AndorsTrailApplication.DEVELOPMENT_VALIDATEDATA) {
						L.log("OPTIMIZE: Unrecognized requirement type: "+p.value);
					}
				}
			} else if (p.name.equalsIgnoreCase("requireId")) {
				requireId = p.value;
			} else if (p.name.equalsIgnoreCase("requireValue")) {
				requireValue = Integer.parseInt(p.value);
			} else if (p.name.equalsIgnoreCase("requireNegation")) {
				requireNegation = Boolean.parseBoolean(p.value);
			}
		}
		if (requireType == null) return null;
		if (requireType == Requirement.RequirementType.random)
		{
			requireChance = ResourceParserUtils.parseChance(requireId);
			requireId = null;
		}

		return new Requirement(requireType, requireId, requireValue, requireNegation, requireChance);
	}

	private static CoordRect getTMXObjectPosition(TMXObject object, TMXMap m) {
		final Coord topLeft = new Coord(
				Math.round(((float)object.x) / m.tilewidth)
				,Math.round(((float)object.y) / m.tileheight)
		);
		final int width = Math.round(((float)object.width) / m.tilewidth);
		final int height = Math.round(((float)object.height) / m.tileheight);
		return new CoordRect(topLeft, new Size(width, height));
	}

	private static final String LAYERNAME_GROUND = "ground";
	private static final String LAYERNAME_OBJECTS = "objects";
	private static final String LAYERNAME_ABOVE = "above";
	private static final String LAYERNAME_TOP = "top";
	private static final String LAYERNAME_WALKABLE = "walkable";
	private static final String PROPNAME_FILTER = "colorfilter";
	private static final SetOfLayerNames defaultLayerNames = new SetOfLayerNames(LAYERNAME_GROUND, LAYERNAME_OBJECTS, LAYERNAME_ABOVE, LAYERNAME_TOP, LAYERNAME_WALKABLE);

	private static LayeredTileMap transformMap(TMXLayerMap map, TileCache tileCache) {
		final Size mapSize = new Size(map.width, map.height);
		LayeredTileMap.ColorFilterId colorFilter = LayeredTileMap.ColorFilterId.none;
		for (TMXProperty prop : map.properties) {
			if (prop.name.equalsIgnoreCase(PROPNAME_FILTER)) {
				String filterId = prop.value;
				if (filterId != null) {
					colorFilter = LayeredTileMap.ColorFilterId.valueOf(filterId);
				}
			}
		}
		HashSet<Integer> usedTileIDs = new HashSet<Integer>();
		HashMap<String, TMXLayer> layersPerLayerName = new HashMap<String, TMXLayer>();
		for (TMXLayer layer : map.layers) {
			String layerName = layer.name;
			assert(layerName != null);
			assert(layerName.length() > 0);
			layerName = layerName.toLowerCase();
			if (AndorsTrailApplication.DEVELOPMENT_VALIDATEDATA) {
				if (layersPerLayerName.containsKey(layerName)) {
					L.log("WARNING: Map \"" + map.name + "\" contains multiple layers with name \"" + layerName + "\".");
				}
			}
			layersPerLayerName.put(layerName, layer);
		}

		MapSection defaultLayout = transformMapSection(map,
				tileCache,
				new CoordRect(new Coord(0,0), mapSize),
				layersPerLayerName,
				usedTileIDs,
				defaultLayerNames);

		ArrayList<ReplaceableMapSection> replaceableSections = new ArrayList<ReplaceableMapSection>();
		for (TMXObjectGroup objectGroup : map.objectGroups) {
			for(TMXObject obj : objectGroup.objects) {
				if ("replace".equals(obj.type)) {
					final CoordRect position = getTMXObjectPosition(obj, map);
					SetOfLayerNames layerNames = new SetOfLayerNames();
					for (TMXProperty prop : obj.properties) {
						if (prop.name.equalsIgnoreCase(LAYERNAME_GROUND)) layerNames.groundLayerName = prop.value;
						else if (prop.name.equalsIgnoreCase(LAYERNAME_OBJECTS)) layerNames.objectsLayerName = prop.value;
						else if (prop.name.equalsIgnoreCase(LAYERNAME_ABOVE)) layerNames.aboveLayersName = prop.value;
						else if (prop.name.equalsIgnoreCase(LAYERNAME_TOP)) layerNames.topLayersName = prop.value;
						else if (prop.name.equalsIgnoreCase(LAYERNAME_WALKABLE)) layerNames.walkableLayersName = prop.value;
						else if (AndorsTrailApplication.DEVELOPMENT_VALIDATEDATA) {
							if (!requirementPropertiesNames.contains(prop.name.toLowerCase()))
								L.log("OPTIMIZE: Map " + map.name + " contains replace area with unknown property \"" + prop.name + "\".");
						}
					}
					MapSection replacementSection = transformMapSection(map, tileCache, position, layersPerLayerName, usedTileIDs, layerNames);
					Requirement req = parseRequirement(obj);
					if (req == null || !req.isValid()) {
						QuestProgress qp = QuestProgress.parseQuestProgress(obj.name);
						if (qp != null) req = new Requirement(qp);
					}
					if (req == null || !req.isValid()) {
						if (AndorsTrailApplication.DEVELOPMENT_VALIDATEDATA) {
							L.log("WARNING: Map " + map.name + " contains replace area "+obj.name+" with unparsable requirement");
						}
						continue;
					}
					replaceableSections.add(new ReplaceableMapSection(position, replacementSection, req, objectGroup.name));
				}
			}
		}

		ReplaceableMapSection[] replaceableSections_ = null;
		if (!replaceableSections.isEmpty()) {
			replaceableSections_ = replaceableSections.toArray(new ReplaceableMapSection[replaceableSections.size()]);
		}
		return new LayeredTileMap(mapSize, defaultLayout, replaceableSections_, colorFilter, usedTileIDs);
	}

	private static MapSection transformMapSection(
			TMXLayerMap srcMap,
			TileCache tileCache,
			CoordRect area,
			HashMap<String, TMXLayer> layersPerLayerName,
			HashSet<Integer> usedTileIDs,
			SetOfLayerNames layerNames
	) {

		final MapLayer layerGround = transformMapLayer(layersPerLayerName, layerNames.groundLayerName, srcMap, tileCache, area, usedTileIDs);
		final MapLayer layerObjects = transformMapLayer(layersPerLayerName, layerNames.objectsLayerName, srcMap, tileCache, area, usedTileIDs);
		final MapLayer layerAbove = transformMapLayer(layersPerLayerName, layerNames.aboveLayersName, srcMap, tileCache, area, usedTileIDs);
		final MapLayer layerTop = transformMapLayer(layersPerLayerName, layerNames.topLayersName, srcMap, tileCache, area, usedTileIDs);
		boolean[][] isWalkable = transformWalkableMapLayer(findLayer(layersPerLayerName, layerNames.walkableLayersName, srcMap.name), area);
		byte[] layoutHash = calculateLayoutHash(srcMap, layersPerLayerName, layerNames);
		return new MapSection(layerGround, layerObjects, layerAbove, layerTop, isWalkable, layoutHash);
	}

	private static TMXLayer findLayer(HashMap<String, TMXLayer> layersPerLayerName, String layerName, String mapName) {
		if (layerName == null) return null;
		if (layerName.length() == 0) return null;
		TMXLayer result = layersPerLayerName.get(layerName.toLowerCase());
		if (AndorsTrailApplication.DEVELOPMENT_VALIDATEDATA) {
			if (result == null) {
				L.log("WARNING: Cannot find maplayer \"" + layerName + "\" requested by map \"" + mapName + "\".");
			}
		}
		return result;
	}

	private static MapLayer transformMapLayer(
			HashMap<String, TMXLayer> layersPerLayerName,
			String layerName,
			TMXLayerMap srcMap,
			TileCache tileCache,
			CoordRect area,
			HashSet<Integer> usedTileIDs
	) {
		TMXLayer srcLayer = findLayer(layersPerLayerName, layerName, srcMap.name);
		if (srcLayer == null) return null;
		final MapLayer result = new MapLayer(area.size);
		Tile tile = new Tile();
		for (int dy = 0, sy = area.topLeft.y; dy < area.size.height; ++dy, ++sy) {
			for (int dx = 0, sx = area.topLeft.x; dx < area.size.width; ++dx, ++sx) {
				int gid = srcLayer.gids[sx][sy];
				if (gid <= 0) continue;

				if (!getTile(srcMap, gid, tile)) continue;

				int tileID = tileCache.getTileID(tile.tilesetName, tile.localId);
				result.tiles[dx][dy] = tileID;
				usedTileIDs.add(tileID);
			}
		}
		return result;
	}

	private static boolean[][] transformWalkableMapLayer(TMXLayer srcLayer, CoordRect area) {
		if (srcLayer == null) return null;
		final boolean[][] isWalkable = new boolean[area.size.width][area.size.height];
		for (int x = 0; x < area.size.width; ++x) {
			Arrays.fill(isWalkable[x], true);
		}
		for (int dy = 0, sy = area.topLeft.y; dy < area.size.height; ++dy, ++sy) {
			for (int dx = 0, sx = area.topLeft.x; dx < area.size.width; ++dx, ++sx) {
				int gid = srcLayer.gids[sx][sy];
				if (gid > 0) {
					isWalkable[dx][dy] = false;
				}
			}
		}
		return isWalkable;
	}

	private static byte[] calculateLayoutHash(TMXLayerMap map, HashMap<String, TMXLayer> layersPerLayerName, SetOfLayerNames layerNames) {
		try {
			MessageDigest digest = MessageDigest.getInstance("MD5");
			digestLayer(layersPerLayerName, layerNames.groundLayerName, map, digest);
			digestLayer(layersPerLayerName, layerNames.objectsLayerName, map, digest);
			digestLayer(layersPerLayerName, layerNames.aboveLayersName, map, digest);
			digestLayer(layersPerLayerName, layerNames.topLayersName, map, digest);
			return digest.digest();
		} catch (NoSuchAlgorithmException e) {
			L.log("ERROR: Failed to create layout hash for map " + map.name + " : " + e.toString());
		}
		return new byte[0];
	}

	private static void digestLayer(HashMap<String, TMXLayer> layersPerLayerName, String layerName, TMXLayerMap map, MessageDigest digest) {
		TMXLayer srcLayer = findLayer(layersPerLayerName, layerName, map.name);
		if (srcLayer == null) return;
		if (srcLayer.layoutHash == null) return;
		digest.update(srcLayer.layoutHash);
	}
	
	private static boolean getTile(final TMXLayerMap map, final int gid, final Tile dest) {
		for(int i = map.tileSets.length - 1; i >= 0; --i) {
			TMXTileSet ts = map.tileSets[i];
			if (ts.firstgid <= gid) {
				dest.tilesetName = ts.name;
				dest.localId = (gid - ts.firstgid);
				return true;
			}
		}
		L.log("WARNING: Cannot find tile for gid " + gid);
		return false;
	}

	private static final class Tile {
		public String tilesetName;
		public int localId;
	}

	private static final class SetOfLayerNames {
		public String groundLayerName;
		public String objectsLayerName;
		public String aboveLayersName;
		public String topLayersName;
		public String walkableLayersName;
		public SetOfLayerNames() {
			this.groundLayerName = null;
			this.objectsLayerName = null;
			this.aboveLayersName = null;
			this.topLayersName = null;
			this.walkableLayersName = null;
		}
		public SetOfLayerNames(String groundLayerName, String objectsLayerName, String aboveLayersName, String topLayersName, String walkableLayersName) {
			this.groundLayerName = groundLayerName;
			this.objectsLayerName = objectsLayerName;
			this.aboveLayersName = aboveLayersName;
			this.topLayersName = topLayersName;
			this.walkableLayersName = walkableLayersName;
		}
	}
}
