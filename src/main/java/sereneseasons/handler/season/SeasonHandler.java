/*******************************************************************************
 * Copyright 2016, the Biomes O' Plenty Team
 * 
 * This work is licensed under a Creative Commons Attribution-NonCommercial-NoDerivatives 4.0 International Public License.
 * 
 * To view a copy of this license, visit http://creativecommons.org/licenses/by-nc-nd/4.0/.
 ******************************************************************************/
package sereneseasons.handler.season;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RegistryKey;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import net.minecraft.world.storage.DimensionSavedDataManager;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.network.PacketDistributor;
import sereneseasons.api.SSGameRules;
import sereneseasons.api.config.SeasonsOption;
import sereneseasons.api.config.SyncedConfig;
import sereneseasons.api.season.ISeasonState;
import sereneseasons.api.season.Season;
import sereneseasons.api.season.SeasonHelper;
import sereneseasons.config.SeasonsConfig;
import sereneseasons.handler.PacketHandler;
import sereneseasons.network.message.MessageSyncSeasonCycle;
import sereneseasons.season.SeasonSavedData;
import sereneseasons.season.SeasonTime;

import java.util.HashMap;
import java.util.function.Supplier;

public class SeasonHandler implements SeasonHelper.ISeasonDataProvider
{
    @SubscribeEvent
    public void onWorldTick(TickEvent.WorldTickEvent event)
    {
        World world = event.world;

        if (event.phase == TickEvent.Phase.END && !world.isClientSide)
        {
            if (!SyncedConfig.getBooleanValue(SeasonsOption.PROGRESS_SEASON_WHILE_OFFLINE))
            {
                MinecraftServer server = world.getServer();
                if (server != null && server.getPlayerList().getPlayerCount() == 0)
                    return;
            }

            // Only tick seasons if the game rule is enabled
            if (!world.getGameRules().getBoolean(SSGameRules.RULE_DOSEASONTICK))
                return;
                
            SeasonSavedData savedData = getSeasonSavedData(world);

            if (savedData.seasonCycleTicks++ > SeasonTime.ZERO.getCycleDuration())
            {
                savedData.seasonCycleTicks = 0;
            }
            
            if (savedData.seasonCycleTicks % 20 == 0)
            {
                sendSeasonUpdate(world);
            }

            savedData.setDirty();
        }
    }
    
    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event)
    {
        PlayerEntity player = event.getPlayer();
        World world = player.level;
        
        sendSeasonUpdate(world);
    }

    private Season.SubSeason lastSeason = null;
    public static final HashMap<RegistryKey<World>, Integer> clientSeasonCycleTicks = new HashMap<>();
    public static SeasonTime getClientSeasonTime() {
        Integer i = clientSeasonCycleTicks.get(Minecraft.getInstance().level.dimension());
    	return new SeasonTime(i == null ? 0 : i);
    }
    
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) 
    {
        //Only do this when in the world
        if (Minecraft.getInstance().player == null) return;
        RegistryKey<World> dimension = Minecraft.getInstance().player.level.dimension();

        if (event.phase == TickEvent.Phase.END && SeasonsConfig.isDimensionWhitelisted(dimension))
        {
            clientSeasonCycleTicks.compute(dimension, (k, v) -> v == null ? 0 : v + 1);
        	
            //Keep ticking as we're synchronized with the server only every second
            if (clientSeasonCycleTicks.get(dimension) > SeasonTime.ZERO.getCycleDuration())
            {
                clientSeasonCycleTicks.put(dimension, 0);
            }
            
            SeasonTime calendar = new SeasonTime(clientSeasonCycleTicks.get(dimension));
            
            if (calendar.getSubSeason() != lastSeason)
            {
                Minecraft.getInstance().levelRenderer.allChanged();
                lastSeason = calendar.getSubSeason();
            }
        }
    }

    // TODO: Chunk population. See ChunkStatus
//    @SubscribeEvent
//    public void onPopulateChunk(PopulateChunkEvent.Populate event)
//    {
//        if (!event.getWorld().isRemote && event.getType() != PopulateChunkEvent.Populate.EventType.ICE || !SeasonsConfig.isDimensionWhitelisted(event.getWorld().provider.getDimension()))
//            return;
//
//        event.setResult(Event.Result.DENY);
//        BlockPos blockpos = new BlockPos(event.getChunkX() * 16, 0, event.getChunkZ() * 16).add(8, 0, 8);
//
//        for (int k2 = 0; k2 < 16; ++k2)
//        {
//            for (int j3 = 0; j3 < 16; ++j3)
//            {
//                BlockPos blockpos1 = event.getWorld().getPrecipitationHeight(blockpos.add(k2, 0, j3));
//                BlockPos blockpos2 = blockpos1.down();
//
//                if (SeasonASMHelper.canBlockFreezeInSeason(event.getWorld(), blockpos2, false, SeasonHelper.getSeasonState(event.getWorld()), true))
//                {
//                    event.getWorld().setBlockState(blockpos2, Blocks.ICE.getDefaultState(), 2);
//                }
//
//                if (SeasonASMHelper.canSnowAtInSeason(event.getWorld(), blockpos1, true, SeasonHelper.getSeasonState(event.getWorld()), true))
//                {
//                    event.getWorld().setBlockState(blockpos1, Blocks.SNOW_LAYER.getDefaultState(), 2);
//                }
//            }
//        }
//    }
    
    public static void sendSeasonUpdate(World world)
    {
        if (!world.isClientSide)
        {
            SeasonSavedData savedData = getSeasonSavedData(world);
            PacketHandler.HANDLER.send(PacketDistributor.ALL.noArg(), new MessageSyncSeasonCycle(world.dimension(), savedData.seasonCycleTicks));
        }
    }
    
    public static SeasonSavedData getSeasonSavedData(World w)
    {
        if (w.isClientSide() || !(w instanceof ServerWorld))
        {
            return null;
        }

        ServerWorld world = (ServerWorld)w;
        DimensionSavedDataManager saveDataManager = world.getChunkSource().getDataStorage();

        Supplier<SeasonSavedData> defaultSaveDataSupplier = () ->
        {
            SeasonSavedData savedData = new SeasonSavedData(SeasonSavedData.DATA_IDENTIFIER);

            int startingSeason = SyncedConfig.getIntValue(SeasonsOption.STARTING_SUB_SEASON);

            if (startingSeason == 0)
            {
                savedData.seasonCycleTicks = (world.random.nextInt(12)) * SeasonTime.ZERO.getSubSeasonDuration();
            }
            if (startingSeason > 0)
            {
                savedData.seasonCycleTicks = (startingSeason - 1) * SeasonTime.ZERO.getSubSeasonDuration();
            }

            savedData.setDirty(); //Mark for saving
            return savedData;
        };

        return saveDataManager.computeIfAbsent(defaultSaveDataSupplier, SeasonSavedData.DATA_IDENTIFIER);
    }
    
    //
    // Used to implement getSeasonState in the API
    //
    
    public ISeasonState getServerSeasonState(World world)
    {
        SeasonSavedData savedData = getSeasonSavedData(world);
        return new SeasonTime(savedData.seasonCycleTicks);
    }
    
    public ISeasonState getClientSeasonState()
    {
        Integer i = clientSeasonCycleTicks.get(Minecraft.getInstance().level.dimension());
    	return new SeasonTime(i == null ? 0 : i);
    }
}
