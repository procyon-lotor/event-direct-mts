package org.zhenchao.zelus.summary;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.LineIterator;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.zhenchao.zelus.common.Constants;
import org.zhenchao.zelus.common.GlobalParam;
import org.zhenchao.zelus.common.nlp.StanfordNLPTools;
import org.zhenchao.zelus.common.pojo.Pair;
import org.zhenchao.zelus.common.pojo.Vector;
import org.zhenchao.zelus.common.pojo.Word;
import org.zhenchao.zelus.common.util.SerializeUtils;
import org.zhenchao.zelus.common.util.VectorOperator;
import org.zhenchao.zelus.common.util.ZelusUtils;
import org.zhenchao.zelus.domain.ClustItemPlus;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于子模函数生成多文档摘要(利用向量来度量句子之间的相似度)<br>
 * 向量不实时获取，采用预处理， 并且先对clust按照path得分均值排序
 *
 * @author zhenchao.wang 2016-1-27 20:24:18
 */
public class SummaryBuilderByPreVectorReRanker implements Callable<Boolean>, Constants {

    private static final Logger log = Logger.getLogger(SummaryBuilderByPreVectorReRanker.class);

    /**
     * 分类目录，用于同时跑多个任务
     */
    private final String numDir;

    /**
     * 主题文件名
     */
    private final String filename;

    /**
     * 主题名称
     */
    private final String topicname;

    /**
     * IDF值
     */
    Map<String, Double> idfValues;

    /**
     * topic query
     */
    private final String question;

    /** 词向量获取器 */
    /*private final EhCacheUtil ehCacheUtil;*/

    /**
     * 当前duc下所有词的词向量
     */
    private final Map<String, Vector> wordVecs;

    /**
     * alpha 参数
     */
    private final float alpha;

    /**
     * beta 参数
     */
    private final float beta;

    /**
     * 相似度上限
     */
    private final float similarityThresh;

    /**
     * 每个主题下面选取的句子的数量
     */
    private Integer sentCountInClust = 10;

    public SummaryBuilderByPreVectorReRanker(String numDir, String filename, int sentCountInClust, Map<String, Double> idfValues, String question, Map<String, Vector> wordVecs, float alpha, float beta, float similarityThresh) {
        super();
        this.numDir = StringUtils.isEmpty(numDir) ? "" : ("/" + numDir.trim());
        this.filename = filename;
        this.topicname = this.filename.substring(0, this.filename.length() - 4);
        this.sentCountInClust = Integer.valueOf(sentCountInClust);
        this.idfValues = idfValues;
        this.question = question;
        this.wordVecs = wordVecs;
        this.alpha = alpha;
        this.beta = beta;
        this.similarityThresh = similarityThresh;
    }

    @Override
    public Boolean call() throws Exception {

        log.info("[Thread id:" + Thread.currentThread().getId() + "] is building summary for[" + GlobalParam.workDir + "/" + DIR_SUMMARY_RESULTS + "/" + DIR_SENTENCES_COMPRESSION + "/" + this.filename + "]");

        // 加载当前主题下面的句子，每个类别控制句子数量
        List<ClustItemPlus> candidateSentences = this.loadSentences(this.sentCountInClust);

        // 按照clust的权重对clust重排序
        Collections.sort(candidateSentences);

        // 加载每个clust的权值
        String clusterWeightFilepath = GlobalParam.workDir + "/" + DIR_EVENTS_CLUST + "/" + OBJ + "/" + DIR_CLUSTER_WEIGHT + "/" + this.filename.substring(0, this.filename.length() - 4) + "." + OBJ;
        log.info("loading serilized file[" + clusterWeightFilepath + "]");
        Map<String, Float> clusterWeights = null;
        try {
            clusterWeights = (Map<String, Float>) SerializeUtils.readObj(clusterWeightFilepath);
        } catch (IOException e) {
            log.error("load serilized file[" + clusterWeightFilepath + "] error!", e);
            throw e;
        }
        log.info("load serilized file[" + clusterWeightFilepath + "] successed!");

        /*
         * 在保证摘要总字数不超过规定字数的前提下， 按照句子的综合得分（主题贡献分，查询覆盖度，多样性得分）循环从候选句子中选取句子
         */
        // 当前摘要字数
        int summaryWordCount = 0;

        // 当前摘要包含的句子数
        int summarySentenceCount = 0;

        // 判断候选集合中是否还有句子
        boolean isNotEmpty = true;

        // 对问句进行分词，计算句子向量
        List<Word> questionWords = StanfordNLPTools.segmentWord(this.question.trim());
        Double[] questionVec = this.sentenceToVector(questionWords);

        if (questionVec == null) {
            log.error("The question[" + this.question + "] vector is null!");
            return false;
        }

        /* 存放摘要的中间值，以及最终的摘要，按照clust进行组织 */
        Map<String, List<Pair<Float, String>>> partialSummary = new HashMap<String, List<Pair<Float, String>>>();

        /* 摘要中间值中句子向量 */
        List<Double[]> psVectors = new ArrayList<Double[]>();

        /* 摘要中间值中各词的词频 */
        Map<String, Integer> wordFrequencyInPartialSummary = new HashMap<String, Integer>();

        /* 缓存摘要中每个类的多样性得分 */
        //Map<String, Double> clusterDiversies = new HashMap<String, Double>();

        while (isNotEmpty && summaryWordCount < MAX_SUMMARY_WORDS_COUNT) {

            isNotEmpty = false;

            // 记录当前最大的综合得分
            float maxGeneralScore = Float.NEGATIVE_INFINITY;
            // 计算最大综合得分对应的clust名称
            String selectedClustName = null;
            // 记录最大综合得分对应的clust
            ClustItemPlus selectedClust = null;
            // 记录最大综合得分对应的句子的序号
            Pair<Float, String> selectedSentence = null;
            // 记录最大综合得分对应类别的新的多样性得分
            //double selectedClustDiversityScore = -1.0D;

            for (ClustItemPlus clustItem : candidateSentences) {

                // 当前类别名称
                String clustname = clustItem.getName();

                // 当前类别下剩余的候选句子集合
                List<Pair<Float, String>> pairs = clustItem.getSentences();

                if (CollectionUtils.isEmpty(pairs)) {
                    // 当前类别下已没有候选句子
                    continue;
                }

                // 说明还有候选句子
                isNotEmpty = true;

                // 获取当前cluster的权值
                float currentClusterWeight = clusterWeights.get(clustname);

                /* 历史多样性得分 */
                float historyDiversityScore = 0.0f;

                // 综合得分
                float generalScore = 0.0f;

                // 遍历处理当前类别下的句子
                Iterator<Pair<Float, String>> pairItr = pairs.iterator();
                while (pairItr.hasNext()) {
                    Pair<Float, String> pair = pairItr.next();
                    // 1.计算当前句子的主题贡献分
                    float topicScore = currentClusterWeight / (pair.getLeft() * clustItem.getSize());
                    // float topicScore = 0.001f / pair.getLeft();

                    // 2.计算当前句子的查询覆盖度
                    float queryScore = 0.0f;

                    String sentence = pair.getRight();

                    // 计算当前句子与问句的相似度
                    List<Word> words = StanfordNLPTools.segmentWord(sentence.trim());

                    // 计算当前句子的长度，忽略长度小于8的句子
                    if (ZelusUtils.lessThanEight(words)) {
                        pairItr.remove();
                        continue;
                    }

                    Double[] sentVec = this.sentenceToVector(words);

                    if (null == sentVec) {
                        log.info("The sentence[" + sentence + "] vector is null, ingore it!");
                        pairItr.remove();
                        continue;
                    }

                    queryScore = (float) VectorOperator.cosineDistence(sentVec, questionVec);

                    // 3.计算当前句子的多样性得分
                    //double diversityScore = 0.0;

                    // 当前句子与已有摘要的相似度得分
                    double similarityScore = 0.0;
                    boolean tooSimilarity = false; // 如果候选句子中存在与当前句子在词语构成一模一样的句子则为true
                    for (Double[] psVec : psVectors) {
                        double sps = VectorOperator.cosineDistence(sentVec, psVec);
                        if (sps > this.similarityThresh) {
                            tooSimilarity = true;
                            break;
                        }
                        if (sps > 0) {
                            similarityScore += sps;
                        }
                    }

                    if (tooSimilarity) {
                        // 说明当前句子与已经选取的句子在词语构成上相同，忽略
                        pairItr.remove();
                        continue;
                    }

                    if (psVectors.size() > 0) {
                        similarityScore /= psVectors.size();
                    }

                    // 计算综合得分
                    queryScore = (float) Math.log(queryScore + 1);
                    topicScore = (float) (this.sigmoid(Math.log(topicScore + 1)) - 0.5) * 4 * this.alpha;
                    similarityScore = Math.log(similarityScore + 1) * this.beta;

                    log.debug("[alpha=" + this.alpha + ", beta= " + this.beta + "]query score:" + queryScore + ",\ttopic score:" + topicScore + ",\tsimilarity score:" + similarityScore);
                    generalScore = (float) (queryScore + topicScore - similarityScore);

                    if (generalScore > maxGeneralScore) {
                        maxGeneralScore = generalScore;
                        selectedClustName = clustname;
                        selectedClust = clustItem;
                        selectedSentence = pair;
                        //selectedClustDiversityScore = diversityScore;
                        log.info("[best in clust, alpha=" + this.alpha + ", beta=" + this.beta + "]" + generalScore + "\t" + "query score:" + queryScore + ",\ttopic score:" + topicScore + ",\tsimilarity score:" + similarityScore + "\t" + pair.getRight());
                    }

                }

            }

            // 更新已经选择的摘要
            if (null == selectedClustName || null == selectedClust || null == selectedSentence) {
                log.warn("[" + this.topicname + "]Selected clust or sentence is illegal[selectedClustName = " + selectedClustName + ", selectedSentence = " + selectedSentence + "]");
                continue;
            }

            // 从候选集合中选择最佳句子加入摘要集合中，同时将其从候选集合中删除
            List<Pair<Float, String>> sentences = selectedClust.getSentences();
            int num = -1;
            for (int i = 0; i < sentences.size(); i++) {
                Pair<Float, String> sent = sentences.get(i);
                if (selectedSentence.getRight().equals(sent.getRight())) {
                    num = i;
                    break;
                }
            }

            if (num == -1) {
                log.error("The sentence num is illegal:" + num);
                return false;
            }

            Pair<Float, String> ss = sentences.remove(num);
            List<Pair<Float, String>> clustSentencesInSummary = partialSummary.get(selectedClustName);
            if (null == clustSentencesInSummary) {
                clustSentencesInSummary = new ArrayList<Pair<Float, String>>();
                partialSummary.put(selectedClustName, clustSentencesInSummary);
                log.info("-->\t" + ss.getRight());
            }
            clustSentencesInSummary.add(ss);

            // 更新相关数据
            List<Word> words = StanfordNLPTools.segmentWord(ss.getRight());
            Double[] sentvec = this.sentenceToVector(words);
            if (sentvec == null) {
                continue;
            }
            psVectors.add(sentvec);

            // 1.更新摘要字数
            for (Word word : words) {
                if (ZelusUtils.isPunctuation(word)) {
                    continue;
                }
                if ("'s".equals(word.getName())) {
                    continue;
                }
                ++summaryWordCount;
            }

            // 2.更新摘要包含的句子数
            ++summarySentenceCount;

            // 3.更新摘要中词的词频
            for (Word word : words) {
                Integer freq = wordFrequencyInPartialSummary.get(word.getName().toLowerCase());
                if (null == freq) {
                    freq = 0;
                }
                freq += 1;
                wordFrequencyInPartialSummary.put(word.getName(), freq);
            }

            // 4.更新psVectors
            //clusterDiversies.put(selectedClustName, selectedClustDiversityScore);

        }

        // 保存摘要
        StringBuilder summary = new StringBuilder();
        for (Entry<String, List<Pair<Float, String>>> entry : partialSummary.entrySet()) {
            for (Pair<Float, String> pair : entry.getValue()) {
                String sentence = pair.getRight();
                sentence = sentence.replaceAll("''\\s+", "").replaceAll("``\\s+", "").replaceAll("'\\s+", "").replaceAll("`\\s+", "");
                sentence = sentence.replaceAll("\\s+", " ");
                sentence = sentence.replaceAll("\\s+'s", "'s");
                /*sentence = sentence.replaceAll("-lrb-[\\s\\S]*?-rrb-\\s+", "");*/
                sentence = sentence.replaceAll("-lrb-", "");
                sentence = sentence.replaceAll("-rrb-", "");
                sentence = sentence.endsWith(".") ? (sentence.trim() + "\n") : (sentence.trim() + ".\n");
                summary.append(sentence);
            }
        }

        int indexOfPoint = this.filename.lastIndexOf(".");
        String summaryFilename = this.filename.substring(0, indexOfPoint - 1).toUpperCase() + ".M.250." + this.filename.substring(indexOfPoint - 1, indexOfPoint).toUpperCase() + ".3";
        try {
            File file = FileUtils.getFile(GlobalParam.workDir + "/" + DIR_SUMMARY_RESULTS + "/" + DIR_SUMMARIES_V2 + this.numDir, summaryFilename);
            log.info("Saving summary to file[" + file.getAbsolutePath() + "]");
            FileUtils.writeStringToFile(file, summary.toString().trim(), DEFAULT_CHARSET);
        } catch (IOException e) {
            log.error("Save summary[" + this.filename + "] error!", e);
            throw e;
        }

        log.info("[Thread id:" + Thread.currentThread().getId() + "] build summary for[" + this.topicname + "] finished!");
        return true;
    }

    /**
     * sigmoid函数
     *
     * @param x
     * @return
     */
    private double sigmoid(double x) {
        return 1 / (1 + Math.pow(Math.E, -x));
    }

    /**
     * 计算输入句子的向量
     *
     * @param words
     * @return
     */
    private Double[] sentenceToVector(List<Word> words) {

        Double[] vector = new Double[DIMENSION];
        Arrays.fill(vector, 0.0D);

        int count = 0;
        boolean flag = false;
        for (Word word : words) {

            if (ZelusUtils.isPunctuation(word)) {
                // 跳过标点
                continue;
            }

            /*if (STOPWORDS.contains(word.getLemma())) {
                // 跳过停用词
                continue;
            }*/

            try {
                /*Vector vec = this.ehCacheUtil.getMostSimilarVec(word);*/
                Vector vec = this.wordVecs.get(word.getName() + "-" + word.getPos());
                if (vec == null) {
                    // 如果在词向量中找不到当前的词向量，则跳过
                    continue;
                }
                Float[] floatVec = vec.floatVecs();
                for (int i = 0; i < DIMENSION; i++) {
                    vector[i] += floatVec[i];
                    if (!flag) {
                        flag = true;
                    }
                }
                count++;
            } catch (Exception e) {
                log.error("Get word[" + word + "] vector error!", e);
            }
        }

        if (count > 0) {
            for (int i = 0; i < DIMENSION; i++) {
                vector[i] /= count;
            }
        }

        if (!flag) {
            return null;
        }

        return vector;
    }

    /**
     * 加载压缩后的句子，按类别组织
     *
     * @param count: 每个类别下选取的句子数量
     * @return
     * @throws IOException
     */
    private List<ClustItemPlus> loadSentences(int count) throws IOException {

        List<ClustItemPlus> clustItems = new ArrayList<ClustItemPlus>();

        Pattern pattern = Pattern.compile("(classes_\\d+):");

        try {
            log.info("Loading msc file[" + GlobalParam.workDir + "/" + DIR_SUMMARY_RESULTS + "/" + DIR_SENTENCES_COMPRESSION + "/" + this.filename + "]");
            LineIterator lineIterator = FileUtils.lineIterator(FileUtils.getFile(GlobalParam.workDir + "/" + DIR_SUMMARY_RESULTS + '/' + DIR_SENTENCES_COMPRESSION, this.filename), DEFAULT_CHARSET.toString());

            String currentKey = "";
            int sentCount = 0; // 存储当前选择的句子数
            int totalCount = 0; // 总句子数
            while (lineIterator.hasNext()) {
                String line = lineIterator.nextLine();
                Matcher matcher = pattern.matcher(line);
                if (matcher.find()) {
                    // 当前为classes_
                    currentKey = matcher.group(1);
                    ClustItemPlus clustItem = new ClustItemPlus();
                    clustItem.setName(currentKey);
                    clustItems.add(clustItem);
                    totalCount += sentCount;
                    sentCount = 0;
                } else {
                    ClustItemPlus ci = clustItems.get(clustItems.size() - 1);
                    ci.setSize(ci.getSize() + 1);
                    if (sentCount >= count) {
                        continue;
                    }
                    List<Pair<Float, String>> sentences = ci.getSentences();
                    if (null == sentences) {
                        sentences = new ArrayList<Pair<Float, String>>();
                        ci.setSentences(sentences);
                    }
                    // 将score#sentence转换成(score, sentence)
                    int flagNum = line.indexOf("#");
                    float pathWeight = Float.parseFloat(line.substring(0, flagNum));
                    sentences.add(new Pair<Float, String>(pathWeight, line.substring(flagNum + 1)));
                    ci.setAvgWeight(ci.getAvgWeight() + pathWeight);
                    ++sentCount;
                }
            }

            // 将所有clust的weight取均值
            for (ClustItemPlus clustItemPlus : clustItems) {
                int size = clustItemPlus.getSize() > count ? count : clustItemPlus.getSize();
                clustItemPlus.setAvgWeight(clustItemPlus.getAvgWeight() / size);
            }

            log.info("Load msc file finished[sentence count:" + totalCount + "]");

        } catch (IOException e) {
            log.error("Load msc file[" + GlobalParam.workDir + "/" + DIR_SUMMARY_RESULTS + "/" + DIR_SENTENCES_COMPRESSION + "/" + this.filename + "] error!", e);
            throw e;
        }

        return clustItems;

    }

    public static void main(String[] args) throws Exception {

        String workDir = "E:/dev_workspace/tmp/workspace/duc2007";
        String idfFilename = "duc2007.idf";
        String vecFilename = "duc2007.vec";

        final double TOTAL_PAGE_COUNT = 30000000000.0D;

        Map<String, Double> idfValues = new HashMap<String, Double>();
        File idfFIle = FileUtils.getFile(workDir + "/" + DIR_IDF_FILE, idfFilename);
        log.info("Loading idf value file[" + idfFIle.getAbsolutePath() + "]");
        LineIterator lineIterator = null;
        try {
            lineIterator = FileUtils.lineIterator(idfFIle, DEFAULT_CHARSET.toString());
            while (lineIterator.hasNext()) {
                String line = lineIterator.nextLine();
                String[] strs = line.split("###");
                if (strs.length != 2) {
                    log.warn("Line[" + line + "] format is illegal, ignore it!");
                    continue;
                }
                idfValues.put(strs[0].trim(), Long.parseLong(strs[1]) / TOTAL_PAGE_COUNT);
            }
            log.info("Load idf value file[" + idfFIle.getAbsolutePath() + "] finished!");
        } catch (IOException e) {
            log.error("Load idf value file[" + idfFIle.getAbsolutePath() + "] error!", e);
            throw e;
        } finally {
            if (lineIterator != null) {
                lineIterator.close();
            }
        }

        String question = "Linda Tripp, role that Linda Tripp play in the Clinton Lewinsky affair and the Ken Starr investigation?";

        File vecFile = FileUtils.getFile(workDir + "/" + DIR_VEC_FILE, vecFilename);
        log.info("Loading word vec[" + vecFile.getAbsolutePath() + "]");
        Map<String, Vector> wordVecs = new HashMap<String, Vector>();
        try {
            wordVecs = (Map<String, Vector>) SerializeUtils.readObj(vecFile.getAbsolutePath());
        } catch (ClassNotFoundException e) {
            log.error("Load word vec[" + vecFile.getAbsolutePath() + "] error!", e);
            throw e;
        }
        log.info("Load word vec[" + vecFile.getAbsolutePath() + "] success!");

        if (MapUtils.isEmpty(wordVecs)) {
            log.error("Can't load any word vec[" + vecFile.getAbsolutePath() + "]");
            throw new Exception("Can't load any word vec[" + vecFile.getAbsolutePath() + "]");
        }

        SummaryBuilderByPreVectorReRanker summaryBuilder = new SummaryBuilderByPreVectorReRanker("0", "D0731G.txt", 10, idfValues, question, wordVecs, 0.2f, 3.6f, 1.19f);
        ExecutorService es = Executors.newSingleThreadExecutor();
        Future<Boolean> future = es.submit(summaryBuilder);
        try {
            future.get();
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
        }
        es.shutdown();

    }

}
