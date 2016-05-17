package cn.edu.pku.cbi.mosaichunter;
import java.io.File;
import java.io.IOException;
import java.util.Random;

import cn.edu.pku.cbi.mosaichunter.config.ConfigManager;
import cn.edu.pku.cbi.mosaichunter.reference.ReferenceManager;
import net.sf.samtools.SAMFileReader;
import net.sf.samtools.SAMFileReader.ValidationStringency;
import net.sf.samtools.SAMRecord;
import net.sf.samtools.SAMRecordIterator;


public class BamSiteReader {
    
    private final ReferenceManager referenceManager;
    private final String inputFile;
    private final String indexFile;    
    private final int maxDepth;
    private final int minReadQuality;
    private final int minMappingQuality;
    private final boolean removeDuplicates;
    private final int removeFlags;
    private final long seed;
    private final boolean depthSampling;
    private final Random random;
    
    private SAMFileReader input;
    
    public BamSiteReader(ReferenceManager referenceManager, String inputFile, String indexFile, 
            int maxDepth, int minReadQuality, int minMappingQuality,
            boolean removeDuplicates, int removeFlags) {
        this.referenceManager = referenceManager;
        this.inputFile = inputFile;
        this.indexFile = indexFile;
        this.maxDepth = maxDepth;
        this.minReadQuality = minReadQuality;
        this.minMappingQuality = minMappingQuality;
        this.removeDuplicates = removeDuplicates;
        this.removeFlags = removeFlags;
        this.seed = ConfigManager.getInstance().getLong(null, "seed", System.currentTimeMillis());
        this.depthSampling = ConfigManager.getInstance().getBoolean(null, "depth_sampling", false);
        this.random = new Random(seed);
    }

    public void init() throws IOException {
        input = new SAMFileReader(
                new File(inputFile), 
                indexFile == null || indexFile.isEmpty() ? null : new File(indexFile));
        input.setValidationStringency(ValidationStringency.SILENT);

    }

    public void close() throws Exception {
        if (input != null) {
            input.close();
            input = null;
        }
    }
    
    public Site read(String chr, long position, byte refBase, String alleleOrder) throws Exception  {       
        if (chr == null || chr.trim().isEmpty()) {
            throw new IllegalArgumentException("chr is missing");
        }
        if (position <= 0) {
            throw new IllegalArgumentException("position is invalid");
        }
        
        SAMRecordIterator it = input.queryOverlapping(chr, (int) position, (int) position);
        int depth = 0;
        int realDepth = 0;

        SAMRecord[] baseRecords = new SAMRecord[maxDepth + 1];
        short[] basePos = new short[maxDepth + 1];
        byte[] bases = new byte[maxDepth + 1];
        byte[] baseQualities = new byte[maxDepth + 1];
        while (it.hasNext()) {
            SAMRecord read = it.next(); 
            if (read.getDuplicateReadFlag() && removeDuplicates) {
                continue;
            }
            if ((read.getFlags() & removeFlags) != 0) {
                continue;
            }
            if (read.getMappingQuality() < minMappingQuality) {
                continue;
            }
            for (short i = 0; i < read.getReadLength(); ++i) {
                if (position == read.getReferencePositionAtReadPosition(i + 1)) {
                    if (read.getBaseQualities()[i] >= minReadQuality) {
                        realDepth++;
                        int ii = -1;
                        if (depth < maxDepth) {
                            ii = depth;
                            depth++;
                        } else if (depthSampling && random.nextInt(realDepth) < maxDepth) {
                            ii = random.nextInt(maxDepth);
                        }
                        if (ii >= 0) {
                            baseRecords[ii] = read;
                            basePos[ii] = i;
                            bases[ii] = read.getReadBases()[i];
                            baseQualities[ii] = read.getBaseQualities()[i];                                
                        }
                    }
                    break;
                }
            }
        }
         
        Site site = new Site(
                         chr,
                         referenceManager.getReferenceId(chr),
                         position,
                         (byte) Character.toUpperCase(refBase),
                         depth,
                         realDepth,
                         bases,
                         baseQualities,
                         baseRecords,
                         basePos,
                         alleleOrder);           
        it.close();
        return site;   
    }    
}