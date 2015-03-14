package cn.edu.pku.cbi.mosaichunter.filter;

import java.util.HashMap;
import java.util.Map;

import org.apache.commons.math3.stat.inference.AlternativeHypothesis;
import org.apache.commons.math3.stat.inference.BinomialTest;

import net.sf.samtools.SAMRecord;
import cn.edu.pku.cbi.mosaichunter.MosaicHunterHelper;
import cn.edu.pku.cbi.mosaichunter.Site;
import cn.edu.pku.cbi.mosaichunter.StatsManager;
import cn.edu.pku.cbi.mosaichunter.config.ConfigManager;
import cn.edu.pku.cbi.mosaichunter.math.FishersExactTest;

public class CompleteLinkageFilter extends BaseFilter {

    public static final double DEFAULT_MAX_P_VALUE = 0.01;
    
    private final double maxPValue;
    
    public CompleteLinkageFilter(String name) {
        this(name,
             ConfigManager.getInstance().getDouble(name, "max_p_value", DEFAULT_MAX_P_VALUE));
    }
    
    public CompleteLinkageFilter(String name, double maxPValue) {
        super(name);
        this.maxPValue = maxPValue;        
    }   
        
    @Override
    public boolean doFilter(Site filterEntry) {  
        
        SAMRecord[] reads = filterEntry.getReads();
        if (!doFilter(filterEntry, reads)) {
            return false;
        }
        
        SAMRecord[] mates = new SAMRecord[filterEntry.getDepth()];
            
        for (int i = 0; i < mates.length; ++i) {
            if (!reads[i].getReadPairedFlag()) {
                continue;
            }
            
            if (mates[i] == null) {
                mates[i] = getContext().getReadsCache().getMate(reads[i]);
            }
            StatsManager.count("mate_query");
            
            // may cause exception for unpaired reads
            if (reads[i].getMateUnmappedFlag()) {
                StatsManager.count("mate_unmapped");
                if (mates[i] != null) {
                    StatsManager.count("mate_unmapped_but_hit");
                }
            } else if (mates[i] == null) {
                StatsManager.count("mate_miss", 1);
                SAMRecord m = getContext().getSAMFileReader().queryMate(reads[i]);
                if (m != null && m.getAlignmentStart() != reads[i].getAlignmentStart()) {
                    mates[i] = m;
                    StatsManager.count("mate_miss_true", 1);
                    /*
                    System.out.println(filterEntry.getRefName() + " " + filterEntry.getRefPos());
                    System.out.println(reads[i].getMateAlignmentStart() + " " + reads[i].getAlignmentStart());
                    System.out.println(mates[i].getMateAlignmentStart() + " " + mates[i].getAlignmentStart());
                    */
                }
                int dis = Math.abs(reads[i].getMateAlignmentStart() - reads[i].getAlignmentStart());
                if (dis > 0) {
                    if (!reads[i].getMateReferenceName().equals(reads[i].getReferenceName())) {
                        StatsManager.count("mate_diff_chr", 1);
                    } else if (dis > 1000000) {
                        StatsManager.count("mate_dis_1000K", 1);
                    } else if (dis > 100000) {
                        StatsManager.count("mate_dis_100K", 1);
                    } else if (dis > 10000) {
                        StatsManager.count("mate_dis_10K", 1);
                    } 
                    
                    if (dis <= 100000) {
                        StatsManager.count("mate_dis", dis);
                    }
                } else {
                    StatsManager.count("mate_dis_zero", 1);
                }
                
            }
        }
        
        boolean result = doFilter(filterEntry, mates);
        return result;
    }    
    
    private boolean doFilter(Site filterEntry, SAMRecord[] reads) {
        String chrName = filterEntry.getRefName();
        int minReadQuality = ConfigManager.getInstance().getInt(null, "min_read_quality", 0);
        int minMappingQuality = ConfigManager.getInstance().getInt(null, "min_mapping_quality", 0);
               

        // find out all related positions
        Map<Integer, PositionEntry> positions = new HashMap<Integer, PositionEntry>();                
        for (int i = 0; i < filterEntry.getDepth(); ++i) {
            
            if (reads[i] == null || !chrName.equals(reads[i].getReferenceName())) {
                continue;
            }
            byte base = filterEntry.getBases()[i];           
            if (base != filterEntry.getMajorAllele() && base != filterEntry.getMinorAllele()) {
                continue;
            }
            boolean isMajor = base == filterEntry.getMajorAllele();               
            for (int j = 0; j < reads[i].getReadLength(); ++j) {
                if (reads[i].getBaseQualities()[j] < minReadQuality) {
                    continue;
                }
                if (reads[i].getMappingQuality() < minMappingQuality) {
                    continue;
                }
                int id = MosaicHunterHelper.BASE_TO_ID[reads[i].getReadBases()[j]];
                if (id < 0) {
                    continue;
                }             
                int pos = reads[i].getReferencePositionAtReadPosition(j + 1);
                if (pos == filterEntry.getRefPos()) {
                    continue;
                }
                PositionEntry entry = positions.get(pos);
                if (entry == null) {
                    entry = new PositionEntry();
                    positions.put(pos, entry);
                }
                entry.count[id]++;
                if (isMajor) {
                    entry.majorCount[id]++;
                } else {
                    entry.minorCount[id]++;
                }                
            }
        }
        
        // for each position
        for (Integer pos : positions.keySet()) {
            PositionEntry entry = positions.get(pos);
            int[] ids = MosaicHunterHelper.sortAlleleCount(entry.count);
            int majorId = ids[0];
            int minorId = ids[1];
            
            //if (entry.majorCount[majorId] + entry.minorCount[minorId] > 1 &&
            //    entry.majorCount[minorId] + entry.minorCount[majorId] > 1) {
            //    continue;
            //}
            
            // TODO: binom.test, changed by Adam_Yyx, 2015-03-09
            int diagonalSum1 = entry.majorCount[majorId] + entry.minorCount[minorId];
            int diagonalSum2 = entry.majorCount[minorId] + entry.minorCount[majorId];
            int totalSum = diagonalSum1 + diagonalSum2;
            int smallDiagonalSum = diagonalSum1;
            if (diagonalSum2 < diagonalSum1) {
                smallDiagonalSum = diagonalSum2;
            }

            double errorRate = 1e-3;
            double pValueCutoff = 0.01;
            if (new BinomialTest().binomialTest(
                    totalSum, smallDiagonalSum, errorRate, AlternativeHypothesis.GREATER_THAN)
                    < pValueCutoff) {
                continue;
            }
                
            double p = FishersExactTest.twoSided(
                    entry.majorCount[majorId], 
                    entry.majorCount[minorId], 
                    entry.minorCount[majorId],
                    entry.minorCount[minorId]);
            
            
            if (p < maxPValue) {
                char major1 = (char) filterEntry.getMajorAllele();
                char minor1 = (char) filterEntry.getMinorAllele();
                char major2 = (char) MosaicHunterHelper.ID_TO_BASE[majorId];
                char minor2 = (char) MosaicHunterHelper.ID_TO_BASE[minorId];
                filterEntry.setMetadata(
                        getName(),
                        new Object[] {
                            pos,
                            "" + major1 + major2 + ":" + entry.majorCount[majorId],
                            "" + major1 + minor2 + ":" + entry.majorCount[minorId],
                            "" + minor1 + major2 + ":" + entry.minorCount[majorId],
                            "" + minor1 + minor2 + ":" + entry.minorCount[minorId],
                            p});
                return false;
            } 
            
        }     
        return true;
    }
    
    private class PositionEntry {
        private int[] majorCount = new int[4];
        private int[] minorCount = new int[4];
        private int[] count = new int[4];
    }
    
}
