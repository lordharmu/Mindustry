package io.anuke.mindustry.net;

import io.anuke.arc.Core;
import io.anuke.arc.collection.ObjectMap;
import io.anuke.arc.collection.ObjectMap.Entry;
import io.anuke.arc.entities.Entities;
import io.anuke.arc.util.Pack;
import io.anuke.arc.util.Time;
import io.anuke.mindustry.content.blocks.Blocks;
import io.anuke.mindustry.entities.Player;
import io.anuke.mindustry.game.GameMode;
import io.anuke.mindustry.game.Team;
import io.anuke.mindustry.game.Teams;
import io.anuke.mindustry.game.Teams.TeamData;
import io.anuke.mindustry.game.Version;
import io.anuke.mindustry.maps.Map;
import io.anuke.mindustry.maps.MapMeta;
import io.anuke.mindustry.world.Tile;
import io.anuke.mindustry.world.blocks.BlockPart;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static io.anuke.mindustry.Vars.*;

public class NetworkIO{

    public static void writeWorld(Player player, OutputStream os){

        try(DataOutputStream stream = new DataOutputStream(os)){
            //--GENERAL STATE--
            stream.writeByte(state.mode.ordinal()); //gamemode
            stream.writeUTF(world.getMap().name); //map name
            stream.writeInt(world.getSector() == null ? invalidSector : world.getSector().pos()); //sector ID
            stream.writeInt(world.getSector() == null ? 0 : world.getSector().completedMissions);

            //write tags
            ObjectMap<String, String> tags = world.getMap().meta.tags;
            stream.writeByte(tags.size);
            for(Entry<String, String> entry : tags.entries()){
                stream.writeUTF(entry.key);
                stream.writeUTF(entry.value);
            }

            stream.writeInt(state.wave); //wave
            stream.writeFloat(state.wavetime); //wave countdown

            stream.writeInt(player.id);
            player.write(stream);

            //--MAP DATA--

            //map size
            stream.writeShort(world.width());
            stream.writeShort(world.height());

            for(int i = 0; i < world.width() * world.height(); i++){
                Tile tile = world.tile(i % world.width(), i / world.width());

                stream.writeByte(tile.getFloorID());
                stream.writeByte(tile.getBlockID());
                stream.writeByte(tile.getElevation());

                if(tile.block() instanceof BlockPart){
                    stream.writeByte(tile.link);
                }else if(tile.entity != null){
                    stream.writeByte(Pack.byteByte(tile.getTeamID(), tile.getRotation())); //team + rotation
                    stream.writeShort((short) tile.entity.health); //health

                    if(tile.entity.items != null) tile.entity.items.write(stream);
                    if(tile.entity.power != null) tile.entity.power.write(stream);
                    if(tile.entity.liquids != null) tile.entity.liquids.write(stream);
                    if(tile.entity.cons != null) tile.entity.cons.write(stream);

                    tile.entity.writeConfig(stream);
                    tile.entity.write(stream);
                }else if(tile.block() == Blocks.air){
                    int consecutives = 0;

                    for(int j = i + 1; j < world.width() * world.height() && consecutives < 255; j++){
                        Tile nextTile = world.tile(j % world.width(), j / world.width());

                        if(nextTile.getFloorID() != tile.getFloorID() || nextTile.block() != Blocks.air || nextTile.getElevation() != tile.getElevation()){
                            break;
                        }

                        consecutives++;
                    }

                    stream.writeByte(consecutives);
                    i += consecutives;
                }
            }

            stream.write(Team.all.length);

            //write team data
            for(Team team : Team.all){
                TeamData data = state.teams.get(team);
                stream.writeByte(team.ordinal());

                stream.writeByte(data.enemies.size());
                for(Team enemy : data.enemies){
                    stream.writeByte(enemy.ordinal());
                }

                stream.writeByte(data.cores.size);
                for(Tile tile : data.cores){
                    stream.writeInt(tile.pos());
                }
            }

        }catch(IOException e){
            throw new RuntimeException(e);
        }
    }

    public static void loadWorld(InputStream is){

        Player player = players[0];

        try(DataInputStream stream = new DataInputStream(is)){
            Time.clear();

            //general state
            byte mode = stream.readByte();
            String map = stream.readUTF();
            int sector = stream.readInt();
            int missions = stream.readInt();

            if(sector != invalidSector){
                world.sectors.createSector(Pack.leftShort(sector), Pack.rightShort(sector));
                world.setSector(world.sectors.get(sector));
                world.getSector().completedMissions = missions;
            }else{
                world.setSector(null);
            }

            ObjectMap<String, String> tags = new ObjectMap<>();

            byte tagSize = stream.readByte();
            for(int i = 0; i < tagSize; i++){
                String key = stream.readUTF();
                String value = stream.readUTF();
                tags.put(key, value);
            }

            int wave = stream.readInt();
            float wavetime = stream.readFloat();

            state.wave = wave;
            state.wavetime = wavetime;
            state.mode = GameMode.values()[mode];

            Entities.clear();
            int id = stream.readInt();
            player.resetNoAdd();
            player.read(stream);
            player.resetID(id);
            player.add();

            world.beginMapLoad();

            //map
            int width = stream.readShort();
            int height = stream.readShort();

            Map currentMap = new Map(map, new MapMeta(0, new ObjectMap<>(), width, height, null), true, () -> null);
            currentMap.meta.tags.clear();
            currentMap.meta.tags.putAll(tags);
            world.setMap(currentMap);

            Tile[][] tiles = world.createTiles(width, height);

            for(int i = 0; i < width * height; i++){
                int x = i % width, y = i / width;
                byte floorid = stream.readByte();
                byte wallid = stream.readByte();
                byte elevation = stream.readByte();

                Tile tile = new Tile(x, y, floorid, wallid);
                tile.setElevation(elevation);

                if(wallid == Blocks.blockpart.id){
                    tile.link = stream.readByte();
                }else if(tile.entity != null){
                    byte tr = stream.readByte();
                    short health = stream.readShort();

                    byte team = Pack.leftByte(tr);
                    byte rotation = Pack.rightByte(tr);

                    tile.setTeam(Team.all[team]);
                    tile.entity.health = health;
                    tile.setRotation(rotation);

                    if(tile.entity.items != null) tile.entity.items.read(stream);
                    if(tile.entity.power != null) tile.entity.power.read(stream);
                    if(tile.entity.liquids != null) tile.entity.liquids.read(stream);
                    if(tile.entity.cons != null) tile.entity.cons.read(stream);

                    tile.entity.readConfig(stream);
                    tile.entity.read(stream);
                }else if(wallid == 0){
                    int consecutives = stream.readUnsignedByte();

                    for(int j = i + 1; j < i + 1 + consecutives; j++){
                        int newx = j % width, newy = j / width;
                        Tile newTile = new Tile(newx, newy, floorid, wallid);
                        newTile.setElevation(elevation);
                        tiles[newx][newy] = newTile;
                    }

                    i += consecutives;
                }

                tiles[x][y] = tile;
            }

            state.teams = new Teams();

            byte teams = stream.readByte();
            for(int i = 0; i < teams; i++){
                Team team = Team.all[stream.readByte()];

                byte enemies = stream.readByte();
                Team[] enemyArr = new Team[enemies];
                for(int j = 0; j < enemies; j++){
                    enemyArr[j] = Team.all[stream.readByte()];
                }

                state.teams.add(team, enemyArr);

                byte cores = stream.readByte();

                for(int j = 0; j < cores; j++){
                    state.teams.get(team).cores.add(world.tile(stream.readInt()));
                }

                if(team == players[0].getTeam() && cores > 0){
                    Core.camera.position.set(state.teams.get(team).cores.first().drawx(), state.teams.get(team).cores.first().drawy());
                }
            }

            world.endMapLoad();

        }catch(IOException e){
            throw new RuntimeException(e);
        }
    }

    public static ByteBuffer writeServerData(){
        int maxlen = 32;

        String host = (headless ? "Server" : players[0].name);
        String map = world.getMap() == null ? "None" : world.getMap().name;

        host = host.substring(0, Math.min(host.length(), maxlen));
        map = map.substring(0, Math.min(map.length(), maxlen));

        ByteBuffer buffer = ByteBuffer.allocate(128);

        buffer.put((byte) host.getBytes(StandardCharsets.UTF_8).length);
        buffer.put(host.getBytes(StandardCharsets.UTF_8));

        buffer.put((byte) map.getBytes(StandardCharsets.UTF_8).length);
        buffer.put(map.getBytes(StandardCharsets.UTF_8));

        buffer.putInt(playerGroup.size());
        buffer.putInt(state.wave);
        buffer.putInt(Version.build);
        buffer.put((byte)Version.type.getBytes(StandardCharsets.UTF_8).length);
        buffer.put(Version.type.getBytes(StandardCharsets.UTF_8));
        buffer.put((byte)state.mode.ordinal());
        return buffer;
    }

    public static Host readServerData(String hostAddress, ByteBuffer buffer){
        byte hlength = buffer.get();
        byte[] hb = new byte[hlength];
        buffer.get(hb);

        byte mlength = buffer.get();
        byte[] mb = new byte[mlength];
        buffer.get(mb);

        String host = new String(hb, StandardCharsets.UTF_8);
        String map = new String(mb, StandardCharsets.UTF_8);

        int players = buffer.getInt();
        int wave = buffer.getInt();
        int version = buffer.getInt();
        byte tlength = buffer.get();
        byte[] tb = new byte[tlength];
        buffer.get(tb);
        String vertype = new String(tb, StandardCharsets.UTF_8);
        GameMode mode = GameMode.values()[buffer.get()];

        return new Host(host, hostAddress, map, wave, players, version, vertype, mode);
    }
}
