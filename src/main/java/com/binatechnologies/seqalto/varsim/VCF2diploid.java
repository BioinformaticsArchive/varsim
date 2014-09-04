package com.binatechnologies.seqalto.varsim;

//--- Java imports ---

import org.apache.log4j.Logger;

import java.io.*;
import java.util.*;

/**
 * Class to construct diploid genome from genome reference and genome variants
 * calls in VCF format.
 *
 * @author Alexej Abyzov, John C. Mu
 */

public class VCF2diploid {
    private final static Logger log = Logger.getLogger(VCF2diploid.class.getName());

    private final static char DELETED_BASE = '~';
    Type _gender;
    private String[] _chrFiles = null, _vcfFiles = null;
    private String _id = "";
    @SuppressWarnings("unchecked")
    private ArrayList<Variant>[] _variants = (ArrayList<Variant>[]) new ArrayList[25];

    public VCF2diploid(String[] chrFiles, String[] vcfFiles, String id,
                       boolean pass, Type gender) {
        _gender = gender;
        _chrFiles = chrFiles;
        _vcfFiles = vcfFiles;
        if (id != null)
            _id = id;

        for (int i = 0; i < _variants.length; i++) {
            _variants[i] = new ArrayList<Variant>(128);
        }

        for (int i = 0; i < vcfFiles.length; i++) {
            VCFparser parser = new VCFparser(vcfFiles[i], _id, pass);
            int n_ev = 0, var_nucs = 0;
            while (parser.hasMoreInput()) {
                Variant var = parser.parseLine();
                if (var == null) {
                    // System.err.println("Bad variant or not a variant line");
                    continue;
                }
                if (var.maternal() == 0 && var.paternal() == 0) {
                    log.warn("Not maternal nor paternal");
                    continue;
                }
                int chr = var.chromosome();
                if (chr <= 0 || chr > _variants.length) {
                    log.warn("Chr out of range, probably unplaced");
                    continue;
                }
                _variants[chr - 1].add(var);
                n_ev++;
                var_nucs += var.variantBases();
            }
            System.out.println(vcfFiles[i] + ": " + n_ev + " variants, "
                    + var_nucs + " variant bases");
        }

        // for (int i = 0;i < _variants.length;i++)
        // System.out.println((i + 1) + " " + _variants[i].size());
    }

    /**
     * Main function.
     */
    public static void main(String[] args) {
        String VERSION = "vcf2diploid_bina - v0.2.6";

        ArrayList<String> chrFiles = new ArrayList<String>(1);
        ArrayList<String> vcfFiles = new ArrayList<String>(1);
        String id = "";
        boolean pass = false;
        Type gender = null;

        String usage = "Usage:\n";
        usage += "\tvcf2diploid -t <male/female> -id <sample_id> [-pass] ";
        usage += "-chr <file.fa> ... ";
        usage += "[-vcf <file.vcf> ...]\n";
        usage += "\tvcf2diploid -version\n";

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-vcf")) {
                while (++i < args.length)
                    if (args[i].charAt(0) != '-')
                        vcfFiles.add(args[i]);
                    else {
                        i--;
                        break;
                    }
            } else if (args[i].equals("-chr")) {
                while (++i < args.length)
                    if (args[i].charAt(0) != '-')
                        chrFiles.add(args[i]);
                    else {
                        i--;
                        break;
                    }
            } else if (args[i].equals("-id")) {
                if (++i < args.length)
                    id = args[i];
            } else if (args[i].equals("-t")) {
                if (++i < args.length) {
                    if (args[i].equalsIgnoreCase("male")) {
                        gender = Type.MALE;
                    } else if (args[i].equalsIgnoreCase("female")) {
                        gender = Type.FEMALE;
                    } else {
                        log.error("Invalid gender\n");
                        log.error(usage);
                        return;
                    }
                }
            } else if (args[i].equals("-version")) {
                System.out.println(VERSION);
                return;
            } else if (args[i].equals("-pass")) {
                pass = true;
            }
        }

        if (id.length() <= 0) {
            log.error("No sample id is given.\n" + usage);
            return;
        }

        if (chrFiles.size() == 0) {
            log.error("No chromosome file(s) is given!\n" + usage);
            return;
        }

        if (vcfFiles.size() == 0) {
            log.error("No VCF file(s) is given!");
        }

        VCF2diploid maker = new VCF2diploid(chrFiles.toArray(new String[0]),
                vcfFiles.toArray(new String[0]), id, pass, gender);
        maker.makeDiploid();
    }

    // This is the main function that makes the diploid genome
    public void makeDiploid() {
        StringBuilder paternal_chains = new StringBuilder();
        StringBuilder maternal_chains = new StringBuilder();
        int chain_id = 1;

        StringBuilder map_string = new StringBuilder();

        // This is the loop if chromosomes exist in separate files
        SimpleReference all_seqs = new SimpleReference();
        for (int f = 0; f < _chrFiles.length; f++) {
            all_seqs.addReference(_chrFiles[f]);
        }

        // This is the loop through each chromosome
        for (int s = 0; s < all_seqs.getNumContigs(); s++) {
            Sequence ref_seq = all_seqs.getSequence(all_seqs.nameAt(s));

            System.out.println("Working on " + ref_seq.getName() + "...");
            int index = variantFileParser.getChromIndex(ref_seq.getName());

            // ignore the unplaced contigs
            if (index < 1 || index > VCFparser.MT) {
                continue;
            }

            boolean output_paternal = true;
            boolean output_maternal = true;
            boolean output_both = output_paternal && output_maternal;

            if (_gender == Type.FEMALE) {
                if (index == VCFparser.Y) {
                    output_paternal = false;
                    output_maternal = false;
                }
            } else if (_gender == Type.MALE) {
                // only male and female
                if (index == VCFparser.X) {
                    output_paternal = false;
                }
                if (index == VCFparser.Y) {
                    output_maternal = false;
                }
            }
            if (index == VCFparser.MT) {
                output_paternal = false;
            }

            if (!output_paternal && !output_maternal) {
                // skip chromosome
                continue;
            }

            // this is the list of variants for the chromosome of question
            ArrayList<Variant> varList = _variants[index - 1];

            ArrayList<Boolean> maternal_added_variants = new ArrayList<Boolean>();
            ArrayList<Boolean> paternal_added_variants = new ArrayList<Boolean>();

            int len = ref_seq.length();
            byte[] maternal_seq = new byte[len];
            byte[] paternal_seq = new byte[len];
            // byte[] ins_flag = new byte[len];
            // Flag specification:
            // b -- insertion in both haplotypes
            // p -- insertion in paternal haplotype
            // m -- insertion in maternal haplotype

            // fill both maternal and paternal with the original reference
            // sequence
            for (int c = 1; c <= len; c++) {
                maternal_seq[c - 1] = paternal_seq[c - 1] = ref_seq.byteAt(c);
            }

            Hashtable<Integer, FlexSeq> pat_ins_seq = new Hashtable<Integer, FlexSeq>(
                    150);
            Hashtable<Integer, FlexSeq> mat_ins_seq = new Hashtable<Integer, FlexSeq>(
                    150);

            int n_var_pat = 0, n_var_mat = 0;
            int n_base_pat = 0, n_base_mat = 0;
            ListIterator<Variant> it = varList.listIterator();
            while (it.hasNext()) {
                // iterate over the variants in the chromosome
                Variant var = it.next();

                if (!var.isPhased()) {
                    var.randomizeHaplotype();
                }

                if (var.paternal() > 0) {
                    if (addVariant(paternal_seq, ref_seq, var, var.paternal(),
                            pat_ins_seq)) {
                        n_var_pat++;
                        n_base_pat += var.variantBases();
                        paternal_added_variants.add(true);
                    } else {
                        paternal_added_variants.add(false);
                    }
                } else {
                    paternal_added_variants.add(false);
                }

                if (var.maternal() > 0) {
                    if (addVariant(maternal_seq, ref_seq, var, var.maternal(),
                            mat_ins_seq)) {
                        n_var_mat++;
                        n_base_mat += var.variantBases();
                        maternal_added_variants.add(true);
                    } else {
                        maternal_added_variants.add(false);
                    }
                } else {
                    maternal_added_variants.add(false);
                }

            }

            log.info("number of variants: " + varList.size());

            writeVCF(ref_seq, varList, paternal_added_variants,
                    maternal_added_variants, output_paternal, output_maternal);

            writeDiploid(ref_seq, paternal_seq, maternal_seq, pat_ins_seq,
                    mat_ins_seq, output_paternal, output_maternal);

            //if (output_both) {
            //writeMap(ref_seq, paternal_seq, maternal_seq, pat_ins_seq,
            //            mat_ins_seq);
            //}

            if (output_paternal) {
                paternal_chains.append(makeChains(ref_seq.getName(),
                        paternalName(ref_seq.getName()), paternal_seq,
                        pat_ins_seq, chain_id));

                makePosMap(map_string, paternalName(ref_seq.getName()), ref_seq, paternal_seq, pat_ins_seq);
            }

            if (output_maternal) {
                maternal_chains.append(makeChains(ref_seq.getName(),
                        maternalName(ref_seq.getName()), maternal_seq,
                        mat_ins_seq, chain_id));

                makePosMap(map_string, maternalName(ref_seq.getName()), ref_seq, maternal_seq, mat_ins_seq);
            }
            chain_id++;

            if (output_paternal) {
                System.out.println("Applied " + n_var_pat + " variants "
                        + n_base_pat + " bases to " + "paternal genome.");
            }
            if (output_maternal) {
                System.out.println("Applied " + n_var_mat + " variants "
                        + n_base_mat + " bases to " + "maternal genome.");
            }
        }

        try {
            FileWriter fw = new FileWriter(new File("paternal.chain"));
            BufferedWriter bw = new BufferedWriter(fw);
            bw.write(paternal_chains.toString());
            bw.newLine();
            bw.close();
            fw.close();
        } catch (IOException ex) {
            log.error(ex.toString());
        }

        try {
            FileWriter fw = new FileWriter(new File("maternal.chain"));
            BufferedWriter bw = new BufferedWriter(fw);
            bw.write(maternal_chains.toString());
            bw.newLine();
            bw.close();
            fw.close();
        } catch (IOException ex) {
            log.error(ex.toString());
        }

        try {
            FileWriter fw = new FileWriter(new File(_id + ".map"));
            BufferedWriter bw = new BufferedWriter(fw);
            bw.write(map_string.toString());
            bw.newLine();
            bw.close();
            fw.close();
        } catch (IOException ex) {
            log.error(ex.toString());
        }
    }

    /*
     * new_seq -- sequence to be modified ref_seq -- reference sequence pos --
     * position of the variant del -- length of reference sequence of the
     * deletion ins -- the insertion string ins_seq -- hashtable that records
     * insertion locations
     */
    private boolean addVariant(byte[] new_seq, Sequence ref_seq,
                               Variant variant, int allele, Hashtable<Integer, FlexSeq> ins_seq) {

        int pos = variant.position();
        int del = variant.deletion();
        byte[] ins = variant.insertion(allele);
        Variant.Type var_type = variant.getType(allele);

        //System.err.println(variant);
        //System.err.println(var_type);

        boolean overlap = false;

        if (pos > new_seq.length || pos + del > new_seq.length) {
            log.warn("Variant out of chromosome bounds at "
                    + ref_seq.getName() + ":" + pos + ", (del,ins) of (" + del
                    + "," + ins + "). Skipping.");
            return false;
        }

        // if any location of this variant overlap a deleted base or a SNP, we
        // skip it
        // insertions may not be next to SNPs or deletions, this is to avoid problem of DUP/INV
        for (int p = pos; p <= pos + del; p++) {
            if (new_seq[p - 1] == DELETED_BASE
                    || new_seq[p - 1] != ref_seq.byteAt(p)) {
                overlap = true;
            }
        }

        if (overlap) {
            try {
                if (ins != null) {
                    log.warn("Variant overlap at " + ref_seq.getName() + ":"
                            + pos + ", (del,ins) of (" + del + "," + new String(ins, "US-ASCII") + "). Skipping.");
                } else {
                    log.warn("Variant overlap at " + ref_seq.getName() + ":"
                            + pos + ", (del,ins) of (" + del + ",<imprecise>). Skipping.");
                }
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
            return false;
        }

        if (del == 1 && (ins != null && ins.length == 1)) { // SNP
            // this is to maintain the reference repeat annotations
            if (Character.isLowerCase((char) ref_seq.byteAt(pos))) {
                new_seq[pos - 1] = (byte) Character.toLowerCase((char) ins[0]);
            } else {
                new_seq[pos - 1] = (byte) Character.toUpperCase((char) ins[0]);
            }
        } else if (var_type == Variant.Type.MNP) {
            // add this as a bunch of SNPs
            // TODO this may result in other variants getting added in between
            for (int i = pos; i < pos + del; i++) {
                // add each SNP
                if (Character.isLowerCase((char) ref_seq.byteAt(i))) {
                    new_seq[i - 1] = (byte) Character.toLowerCase((char) ins[i - pos]);
                } else {
                    new_seq[i - 1] = (byte) Character.toUpperCase((char) ins[i - pos]);
                }
            }

        } else { // Indel, SV
            // check whether the insertion location has been inserted before
            if (ins_seq.get(pos) != null) {
                log.warn("Multiple insertions at "
                        + ref_seq.getName() + ":" + pos);
                try {
                    if (ins != null) {
                        log.warn("Skipping variant with (del,ins) of (" + del
                                + "," + new String(ins, "US-ASCII") + ").");
                    } else {
                        log.warn("Skipping variant with (del,ins) of (" + del
                                + ", <imprecise> ).");
                    }
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                }
                return false;
            }

            if (var_type == Variant.Type.Inversion) {
                // Treat this as deletion then insertion of reverse complement
                //System.err.println("Insert INV");
                del = variant.insertion_len(allele);
                ins = ref_seq.revComp(pos, pos + del);
            }

            if (var_type == Variant.Type.Tandem_Duplication) {
                // treat this as deletion then insertion of several copies
                // this prevents the original sequence to be altered and
                // become less like a duplication
                // TODO make length correct
                //System.err.println("Insert DUP");

                del = variant.insertion_len(allele);
                int single_ins_len = variant.insertion_len(allele);
                byte[] orig_seq = ref_seq.subSeq(pos, pos + single_ins_len);
                ins = new byte[single_ins_len * variant.getCN(allele)];
                System.arraycopy(orig_seq, 0, ins, 0, orig_seq.length);
                for (int i = 0; i < ins.length; i++) {
                    ins[i] = orig_seq[i % (orig_seq.length)];
                }
            }

            // set to deleted base, so that we don't change those in future
            for (int p = pos; p < pos + del; p++) {
                new_seq[p - 1] = DELETED_BASE;
            }

            // matter
            // TODO if ins is null we still need to add??
            if (ins != null && ins.length > 0) {
                // convert to flexseq
                FlexSeq s = new FlexSeq(ins);
                s.setType(variant.getAlt(allele).getType());
                s.setCopy_num(variant.getAlt(allele).getCopy_num());
                s.setLength(variant.getAlt(allele).length());
                s.setVar_id(variant.getVar_id());

                //System.err.println("Real Insert: " + s.getType());

                ins_seq.put(new Integer(pos), s);
            }
        }

        return true;
    }

    private String makeChains(String ref_name, String der_name, byte[] genome,
                              Hashtable<Integer, FlexSeq> ins_seq, int id) {
        boolean[] ins_flag = new boolean[genome.length];
        Enumeration<Integer> enm = ins_seq.keys();
        while (enm.hasMoreElements()) {
            Integer key = enm.nextElement();
            ins_flag[key.intValue() - 1] = true;
        }
        int ref_len = genome.length;
        int der_len = 0;
        int score = 0;
        for (int p = 0; p < genome.length; p++) {
            if (ins_flag[p])
                der_len += ins_seq.get(p + 1).var_length();
            if (genome[p] != DELETED_BASE) {
                der_len++;
                score++;
            }
        }

        StringWriter ret = new StringWriter();
        PrintWriter wr = new PrintWriter(ret);
        wr.println("chain " + score + " " + ref_name + " " + ref_len + " + 0 "
                + ref_len + " " + der_name + " " + der_len + " + 0 " + der_len
                + " " + id);
        int size = 0, dref = 0, dder = 0;
        boolean flag = false;
        for (int p = 0; p < genome.length; p++) {
            if (ins_flag[p]) {
                dder += ins_seq.get(p + 1).var_length();
                flag = true;
            }
            if (genome[p] == DELETED_BASE) {
                dref++;
                flag = true;
            } else { // Normal base
                if (flag) {
                    wr.println(size + " " + dref + " " + dder);
                    size = dref = dder = 0;
                    flag = false;
                }
                size++;
            }
        }
        wr.println(size);
        wr.println();

        return ret.toString();
    }

    private void adjust_idx(map_rec curr_rec, host_ref_idx hf_idx) {
        if (curr_rec.feature.equals("SEQ")) {
            hf_idx.host_idx += curr_rec.len;
            hf_idx.ref_idx += curr_rec.len;
        } else if (curr_rec.feature.equals("DEL")) {
            hf_idx.ref_idx += curr_rec.len;
        } else if (curr_rec.feature.equals("INS")) {
            hf_idx.host_idx += curr_rec.len;
        } else if (curr_rec.feature.equals("DUP_TANDEM")) {
            hf_idx.host_idx += curr_rec.len;
        } else if (curr_rec.feature.equals("INV")) {
            hf_idx.host_idx += curr_rec.len;
        }
    }

    private map_rec new_curr_rec(StringBuilder sb, int idx, String chr_name, String ref_chr_name, host_ref_idx hf_idx,
                                 byte[] genome, boolean[] ins_flag, Hashtable<Integer, FlexSeq> ins_seq) {
        map_rec curr_rec = new map_rec();
        curr_rec.host_chr = chr_name;
        curr_rec.ref_chr = ref_chr_name;

        // compute what the first line is and store as object

        // if it is inserted, we copy the var_id to the deletion
        boolean inserted = false;
        String var_id = ".";
        if (ins_flag[idx]) {
            inserted = true;
            // insertion at this location
            FlexSeq ins = ins_seq.get(idx + 1);
            var_id = ins.getVar_id();

            //System.err.println("Check type: " + ins.getType());

            // need to check what kind of insertion it is
            switch (ins.getType()) {
                case SEQ:
                case INS:
                    curr_rec.host_pos = hf_idx.host_idx;
                    curr_rec.ref_pos = hf_idx.ref_idx - 1;
                    curr_rec.feature = "INS";
                    curr_rec.dir = true;
                    curr_rec.len = ins.var_length();
                    curr_rec.var_id = var_id;

                    break;
                case INV:
                    // TODO: treat inversion like MNP
                    curr_rec.host_pos = hf_idx.host_idx;
                    curr_rec.ref_pos = hf_idx.ref_idx - 1;
                    curr_rec.feature = "INV";
                    curr_rec.dir = false;
                    curr_rec.len = ins.var_length();
                    curr_rec.var_id = var_id;

                    break;
                case DUP:
                    // need to replicate several blocks
                    int cn = ins.getCopy_num();

                    // first build one
                    curr_rec.host_pos = hf_idx.host_idx;
                    curr_rec.ref_pos = hf_idx.ref_idx - 1;
                    curr_rec.feature = "DUP_TANDEM";
                    curr_rec.dir = true;
                    curr_rec.len = ins.length();
                    curr_rec.var_id = var_id;

                    // iterate
                    for (int i = 1; i < cn; i++) {
                        adjust_idx(curr_rec, hf_idx);
                        sb.append(curr_rec);
                        sb.append('\n');
                        curr_rec = new map_rec();
                        curr_rec.host_chr = chr_name;
                        curr_rec.ref_chr = ref_chr_name;
                        curr_rec.host_pos = hf_idx.host_idx;
                        curr_rec.ref_pos = hf_idx.ref_idx - 1;
                        curr_rec.feature = "DUP_TANDEM";
                        curr_rec.dir = true;
                        curr_rec.len = ins.length();
                        curr_rec.var_id = var_id;
                    }

                    break;
            }

            // output it
            adjust_idx(curr_rec, hf_idx);
            sb.append(curr_rec);
            sb.append('\n');

            curr_rec = new map_rec();
            curr_rec.host_chr = chr_name;
            curr_rec.ref_chr = ref_chr_name;

        }

        if (genome[idx] == DELETED_BASE) {
            // deleted base
            curr_rec.host_pos = hf_idx.host_idx - 1;
            curr_rec.ref_pos = hf_idx.ref_idx;
            curr_rec.feature = "DEL";
            curr_rec.dir = true;
            curr_rec.len = 1;
            if (inserted) {
                curr_rec.var_id = var_id;
            }
        } else {
            // regular sequence
            curr_rec.host_pos = hf_idx.host_idx;
            curr_rec.ref_pos = hf_idx.ref_idx;
            curr_rec.feature = "SEQ";
            curr_rec.dir = true;
            curr_rec.len = 1;
        }

        return curr_rec;
    }

    private void makePosMap(StringBuilder sb, String chr_name, Sequence ref_seq, byte[] genome,
                            Hashtable<Integer, FlexSeq> ins_seq) {

        boolean[] ins_flag = new boolean[genome.length];
        Enumeration<Integer> enm = ins_seq.keys();
        while (enm.hasMoreElements()) {
            Integer key = enm.nextElement();
            ins_flag[key.intValue() - 1] = true;
        }


        int NOT_IN_GENOME = 0;


        // host is the perturbed genome
        // ref is b37 or hg19
        // Len is length of the block
        // the positions are the start locations of the block (inclusive)
        // for Insertion and DUP, etc the start location is the location before the event.
        // Feature is annotated in the reads, SEQ, DEL, Insertion, DUP, INV
        // INV is whether the block is inverted
        //bw.write("#Len\tHOST_chr\tHOST_pos\tREF_chr\tREF_pos\tDIRECTION\tFEATURE\tVAR_ID");
        //bw.newLine();

        // TODO deal with var_id

        // iterate though both genomes

        // these are 1-indexed
        host_ref_idx hf_idx = new host_ref_idx();
        hf_idx.host_idx = 1;
        hf_idx.ref_idx = 1;

        map_rec curr_rec = new_curr_rec(sb, 0, chr_name, ref_seq.getName(), hf_idx, genome, ins_flag, ins_seq);

        for (int idx = 1; idx < genome.length; idx++) {
            // if still in the same block increment the length
            boolean same_block = true;

            if (curr_rec.feature.equals("DEL")) {
                if (genome[idx] != DELETED_BASE) {
                    same_block = false;
                } else if (ins_flag[idx]) {
                    same_block = false;
                }
            } else if (curr_rec.feature.equals("SEQ")) {
                if (genome[idx] == DELETED_BASE) {
                    same_block = false;
                } else if (ins_flag[idx]) {
                    same_block = false;
                }
            } else {
                same_block = false;
            }

            if (same_block) {
                curr_rec.len++;
            } else {
                // otherwise output the block
                adjust_idx(curr_rec, hf_idx);
                sb.append(curr_rec);
                sb.append('\n');

                curr_rec = new_curr_rec(sb, idx, chr_name, ref_seq.getName(), hf_idx, genome, ins_flag, ins_seq);
            }

        }

        // make sure the block is outputted?
        sb.append(curr_rec);
        sb.append('\n');

    }

    private void writeMap(Sequence ref_seq, byte[] paternal, byte[] maternal,
                          Hashtable<Integer, byte[]> ins_seq_pat,
                          Hashtable<Integer, byte[]> ins_seq_mat) {
        if (paternal.length != maternal.length) {
            log.error("Paternal and maternal genomes are of "
                    + "different lengths. Making map aborted.");
            return;
        }

        boolean[] ins_flag_pat = new boolean[paternal.length];
        Enumeration<Integer> enm = ins_seq_pat.keys();
        while (enm.hasMoreElements()) {
            Integer key = enm.nextElement();
            ins_flag_pat[key.intValue() - 1] = true;
        }

        boolean[] ins_flag_mat = new boolean[maternal.length];
        enm = ins_seq_mat.keys();
        while (enm.hasMoreElements()) {
            Integer key = (Integer) enm.nextElement();
            ins_flag_mat[key.intValue() - 1] = true;
        }

        int NOT_IN_GENOME = 0;
        String file_name = ref_seq.getName() + "_" + _id + ".map";
        try {
            FileWriter fw = new FileWriter(new File(file_name));
            BufferedWriter bw = new BufferedWriter(fw);

            bw.write("#REF\tPAT\tMAT");

            bw.newLine();
            int ri = 1, pi = 1, mi = 1;
            int pr = NOT_IN_GENOME, pp = NOT_IN_GENOME, pm = NOT_IN_GENOME;
            for (int p = 0; p < paternal.length; p++) {
                boolean for_pat = ins_flag_pat[p];
                boolean for_mat = ins_flag_mat[p];
                boolean for_both = for_pat && for_mat;
                if (for_both) {
                    for_both = Arrays.equals(ins_seq_pat.get(p + 1), ins_seq_mat.get(p + 1));
                }
                if (for_pat || for_mat || for_both) {
                    if (pr > 0 || pp > 0 || pm > 0) {
                        bw.write(pr + "\t" + pp + "\t" + pm);
                        bw.newLine();
                        pr = pp = pm = 0;
                    }
                }

                if (for_both) {
                    byte[] new_seq = ins_seq_pat.get(p + 1);
                    if (new_seq != null) {
                        bw.write(NOT_IN_GENOME + "\t" + pi + "\t" + mi);
                        bw.newLine();
                        for (int i = 0; i < new_seq.length; i++) {
                            pi++;
                            mi++;
                        }
                    }
                } else {
                    if (for_pat) {
                        byte[] new_seq = ins_seq_pat.get(p + 1);
                        if (new_seq != null) {
                            bw.write(NOT_IN_GENOME + "\t" + pi + "\t"
                                    + NOT_IN_GENOME);
                            bw.newLine();
                            for (int i = 0; i < new_seq.length; i++) {
                                pi++;
                            }
                        }
                    }
                    if (for_mat) {
                        byte[] new_seq = ins_seq_mat.get(p + 1);
                        if (new_seq != null) {
                            bw.write(NOT_IN_GENOME + "\t" + NOT_IN_GENOME
                                    + "\t" + mi);
                            bw.newLine();
                            for (int i = 0; i < new_seq.length; i++)
                                mi++;
                        }
                    }
                }
                int ref = ri++, pat = NOT_IN_GENOME, mat = NOT_IN_GENOME;
                if (paternal[p] != DELETED_BASE)
                    pat = pi++;
                if (maternal[p] != DELETED_BASE)
                    mat = mi++;
                if (pr == NOT_IN_GENOME && pp == NOT_IN_GENOME
                        && pm == NOT_IN_GENOME) { // Initiation
                    pr = ref;
                    pp = pat;
                    pm = mat;
                } else {
                    boolean cand = ((pat == NOT_IN_GENOME && pp == NOT_IN_GENOME) || ref
                            - pr == pat - pp)
                            && ((mat == NOT_IN_GENOME && pm == NOT_IN_GENOME) || ref
                            - pr == mat - pm);
                    if (!cand) {
                        bw.write(pr + "\t" + pp + "\t" + pm);
                        bw.newLine();
                        pr = ref;
                        pp = pat;
                        pm = mat;
                    }
                }
            }
            if (pr > 0 || pp > 0 || pm > 0) {
                bw.write(pr + "\t" + pp + "\t" + pm);
                bw.newLine();
            }
            bw.close();
            fw.close();
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    private void writeDiploid(Sequence ref_seq, byte[] paternal,
                              byte[] maternal, Hashtable<Integer, FlexSeq> pat_ins_seq,
                              Hashtable<Integer, FlexSeq> mat_ins_seq, boolean output_paternal,
                              boolean output_maternal) {

        if (output_paternal) {
            String file_name = paternalName(ref_seq.getName() + "_" + _id)
                    + ".fa";
            String name = paternalName(ref_seq.getName());
            try {
                FileWriter fw = new FileWriter(new File(file_name));
                BufferedWriter bw = new BufferedWriter(fw);
                writeGenome(bw, name, paternal, pat_ins_seq);
                bw.close();
                fw.close();
            } catch (IOException ex) {
                log.error(ex.toString());
            }
        }

        if (output_maternal) {
            String file_name = maternalName(ref_seq.getName() + "_" + _id)
                    + ".fa";
            String name = maternalName(ref_seq.getName());
            try {
                FileWriter fw = new FileWriter(new File(file_name));
                BufferedWriter bw = new BufferedWriter(fw);
                writeGenome(bw, name, maternal, mat_ins_seq);
                bw.close();
                fw.close();
            } catch (IOException ex) {
                log.error(ex.toString());
            }
        }
    }

    private void writeGenome(BufferedWriter bw, String name, byte[] genome,
                             Hashtable<Integer, FlexSeq> ins_seq) throws IOException {
        final int line_width = 50; // this is default for FASTA files

        // look-up table for where on the chromosome there are insertions
        boolean[] ins_flag = new boolean[genome.length];
        Enumeration<Integer> enm = ins_seq.keys();
        while (enm.hasMoreElements()) {
            Integer key = enm.nextElement();
            ins_flag[key.intValue() - 1] = true;
        }

        // write header
        bw.write(">" + name);
        bw.newLine();

        StringBuilder line = new StringBuilder();
        for (int p = 0; p < genome.length; p++) {
            if (ins_flag[p]) {
                byte[] new_seq = ins_seq.get(p + 1).getSeq();
                if (new_seq != null && new_seq.length > 0) {
                    line.append(new String(new_seq));
                }
            }
            if (genome[p] != DELETED_BASE) {
                line.append((char) genome[p]);
            }
            while (line.length() >= line_width) {
                bw.write(line.toString(), 0, line_width);
                bw.newLine();
                line.delete(0, line_width);
            }
        }
        while (line.length() > 0) {
            int n = line.length();
            if (line_width < n) {
                n = line_width;
            }
            bw.write(line.toString(), 0, n);
            bw.newLine();
            line.delete(0, n);
        }

    }

    /*
     * Writes out the vcf record for all variants that have added_variants =
     * true
     */
    private void writeVCF(Sequence ref_seq, ArrayList<Variant> varList,
                          ArrayList<Boolean> paternal_added_variants,
                          ArrayList<Boolean> maternal_added_variants,
                          boolean output_paternal, boolean output_maternal) {
        String file_name = ref_seq.getName() + "_" + _id + ".vcf";
        log.info("Writing out the true variants for " + ref_seq.getName());
        try {
            FileWriter fw = new FileWriter(new File(file_name));
            BufferedWriter bw = new BufferedWriter(fw);

            // write header
            bw.write("##fileformat=VCFv4.0\n");
            bw.write("##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Genotype\">\n");
            bw.write("#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tsv\n");

            int num_vars = varList.size();
            for (int i = 0; i < num_vars; i++) {

                if (!output_maternal && output_paternal && !paternal_added_variants.get(i)) {
                    continue;
                } else if (output_maternal && !output_paternal && !maternal_added_variants.get(i)) {
                    continue;
                }

                if (paternal_added_variants.get(i)
                        || maternal_added_variants.get(i)) {

                    // System.err.println("write var: " + i);

                    Variant curr_var = varList.get(i);

                    // chromosome name
                    bw.write(curr_var.getChr_name());
                    bw.write("\t");
                    // start position
                    bw.write(String.valueOf(curr_var.position()
                            - curr_var.getRef_deleted().length()));
                    bw.write("\t");
                    // variant id
                    bw.write(curr_var.getVar_id());
                    bw.write("\t");
                    // ref allele
                    bw.write(curr_var.getOrig_Ref());
                    bw.write("\t");
                    // alt alleles
                    bw.write(curr_var.alt_string().toString());
                    bw.write("\t");
                    // variant quality
                    bw.write(".\t");
                    // pass label
                    bw.write(curr_var.getFilter());
                    bw.write("\t");
                    // INFO
                    // TODO write len here
                    StringBuilder sbStr = new StringBuilder();
                    if (curr_var.getType() == Variant.OverallType.Tandem_Duplication) {
                        sbStr.append("SVTYPE=DUP;");
                        sbStr.append("SVLEN=");
                        sbStr.append(curr_var.getLength());
                    } else if (curr_var.getType() == Variant.OverallType.Inversion) {
                        sbStr.append("SVTYPE=INV;");
                        sbStr.append("SVLEN=");
                        sbStr.append(curr_var.getLength());
                    } else {
                        sbStr.append("SVLEN=");
                        sbStr.append(curr_var.getLength());
                    }
                    bw.write(sbStr.toString());
                    bw.write("\t");
                    // label (GT)
                    boolean hasCN = curr_var.hasCN();
                    if (hasCN) {
                        bw.write("GT:CN\t");
                    } else {
                        bw.write("GT\t");
                    }
                    // the genotype
                    // for this one we need to work out which one is added
                    boolean output_both = output_paternal && output_maternal;

                    sbStr = new StringBuilder();
                    if (output_paternal) {
                        if (paternal_added_variants.get(i)) {
                            sbStr.append(String.valueOf(curr_var.paternal()));
                        } else {
                            sbStr.append("0");
                        }
                    }
                    if (output_both) {
                        sbStr.append("|");
                    }
                    if (output_maternal) {
                        if (maternal_added_variants.get(i)) {
                            sbStr.append(String.valueOf(curr_var.maternal()));
                        } else {
                            sbStr.append("0");
                        }
                    }

                    if (hasCN) {
                        sbStr.append(":");
                        if (output_paternal) {
                            sbStr.append(String.valueOf(curr_var.getCN(curr_var
                                    .paternal())));
                        }
                        if (output_both) {
                            sbStr.append("|");
                        }
                        if (output_maternal) {
                            sbStr.append(String.valueOf(curr_var.getCN(curr_var
                                    .maternal())));
                        }
                    }

                    bw.write(sbStr.toString());
                    bw.newLine();
                }
            }

            bw.close();
            fw.close();

        } catch (IOException ex) {
            ex.printStackTrace();
        }

    }

    private String maternalName(String name) {
        return (name + "_maternal");
    }

    private String paternalName(String name) {
        return (name + "_paternal");
    }

    private enum Type {
        FEMALE, MALE
    }

    //"#Len\tHOST_chr\tHOST_pos\tREF_chr\tREF_pos\tDIRECTION\tFEATURE\tVAR_ID"
    // this is more like a struct :)
    class map_rec {
        public int len = 0;
        public String host_chr = "";
        public int host_pos = 0;
        public String ref_chr = "";
        public int ref_pos = 0;
        public boolean dir = true; // true is forward
        public String feature = ".";
        public String var_id = ".";

        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(len);
            sb.append('\t');
            sb.append(host_chr);
            sb.append('\t');
            sb.append(host_pos);
            sb.append('\t');
            sb.append(ref_chr);
            sb.append('\t');
            sb.append(ref_pos);
            sb.append('\t');
            if (dir) {
                sb.append('+');
            } else {
                sb.append('-');
            }
            sb.append('\t');
            sb.append(feature);
            sb.append('\t');
            sb.append(var_id);

            return sb.toString();
        }
    }

    private class host_ref_idx {
        public int host_idx = 0;
        public int ref_idx = 0;
    }

}