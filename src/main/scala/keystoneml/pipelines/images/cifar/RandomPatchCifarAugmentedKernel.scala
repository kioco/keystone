package keystoneml.pipelines.images.cifar

import scala.reflect.ClassTag

import breeze.linalg._
import breeze.numerics._

import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.rdd.RDD

import scopt.OptionParser

import keystoneml.evaluation.{AugmentedExamplesEvaluator, MulticlassClassifierEvaluator}
import keystoneml.loaders.CifarLoader

import keystoneml.nodes.images._
import keystoneml.nodes.learning.{GaussianKernelGenerator, KernelRidgeRegression, ZCAWhitener, ZCAWhitenerEstimator}
import keystoneml.nodes.stats.{StandardScaler, Sampler}
import keystoneml.nodes.util.{Cacher, ClassLabelIndicatorsFromIntLabels, Shuffler}

import keystoneml.pipelines.FunctionNode
import keystoneml.pipelines.Logging
import keystoneml.workflow.Pipeline
import keystoneml.utils.{MatrixUtils, Stats, Image, ImageUtils}

object RandomPatchCifarAugmentedKernel extends Serializable with Logging {
  val appName = "RandomPatchCifarAugmentedKernel"

  class LabelAugmenter[T: ClassTag](mult: Int) extends FunctionNode[RDD[T], RDD[T]] {
    def apply(in: RDD[T]) = in.flatMap(x => Seq.fill(mult)(x))
  }

  def run(sc: SparkContext, conf: RandomPatchCifarAugmentedKernelConfig): Pipeline[Image, DenseVector[Double]] = {
    // Set up some constants.
    val numClasses = 10
    val numChannels = 3
    val whitenerSize = 100000
    val augmentImgSize = 24
    val flipChance = 0.5

    // Load up training data, and optionally sample.
    val trainData = conf.sampleFrac match {
      case Some(f) => CifarLoader(sc, conf.trainLocation).sample(false, f).cache
      case None => CifarLoader(sc, conf.trainLocation).cache
    }
    val trainImages = ImageExtractor(trainData)

    val patchExtractor = new Windower(conf.patchSteps, conf.patchSize) andThen
      ImageVectorizer.apply andThen
      new Sampler(whitenerSize)

    val (filters, whitener): (DenseMatrix[Double], ZCAWhitener) = {
        val baseFilters = patchExtractor(trainImages)
        val baseFilterMat = Stats.normalizeRows(MatrixUtils.rowsToMatrix(baseFilters), 10.0)
        val whitener = new ZCAWhitenerEstimator(eps=conf.whiteningEpsilon).fitSingle(baseFilterMat)

        //Normalize them.
        val sampleFilters = MatrixUtils.sampleRows(baseFilterMat, conf.numFilters)
        val unnormFilters = whitener(sampleFilters)
        val unnormSq = pow(unnormFilters, 2.0)
        val twoNorms = sqrt(sum(unnormSq(*, ::)))

        ((unnormFilters(::, *) / (twoNorms + 1e-10)) * whitener.whitener.t, whitener)
    }

    val trainImagesAugmented = RandomImageTransformer(flipChance, ImageUtils.flipHorizontal).apply(
      RandomPatcher(conf.numRandomImagesAugment, augmentImgSize, augmentImgSize).apply(
        trainImages))

    val labelExtractor = LabelExtractor andThen ClassLabelIndicatorsFromIntLabels(numClasses)
    val trainLabels = labelExtractor(trainData)
    val trainLabelsAugmented = new LabelAugmenter(conf.numRandomImagesAugment).apply(trainLabels.get)

    val trainImageLabelsShuffled = (new Shuffler[(Image, DenseVector[Double])] andThen
      new Cacher[(Image, DenseVector[Double])](Some("shuffled"))).apply(
      trainImagesAugmented.zip(trainLabelsAugmented))

    val trainImagesShuffled = trainImageLabelsShuffled.get.map(_._1)
    val trainLabelsShuffled = trainImageLabelsShuffled.get.map(_._2)

    val predictionPipeline =
      new Convolver(filters, augmentImgSize, augmentImgSize, numChannels, Some(whitener), true) andThen
        SymmetricRectifier(alpha=conf.alpha) andThen
        new Pooler(conf.poolStride, conf.poolSize, identity, sum(_)) andThen
        ImageVectorizer andThen
        new Cacher[DenseVector[Double]](Some("features")) andThen
        (new StandardScaler, trainImagesShuffled) andThen
        (new KernelRidgeRegression(
            new GaussianKernelGenerator(conf.gamma, conf.cacheKernel),
            conf.lambda.getOrElse(0.0),
            conf.blockSize, // blockSize
            conf.numEpochs, // numEpochs
            conf.seed), // blockPermuter
          trainImagesShuffled, trainLabelsShuffled) andThen
        new Cacher[DenseVector[Double]]

    // Do testing.
    val testData = CifarLoader(sc, conf.testLocation)
    val testImages = ImageExtractor(testData)

    val numTestAugment = 10 // 4 corners, center and flips of each of the 5
    val testImagesAugmented = CenterCornerPatcher(augmentImgSize, augmentImgSize, true).apply(
      testImages)

    // Create augmented image-ids by assiging a unique id to each test image and then
    // augmenting the id
    val testImageIdsAugmented = new LabelAugmenter(numTestAugment).apply(
      testImages.zipWithUniqueId.map(x => x._2))

    val testLabelsAugmented = new LabelAugmenter(numTestAugment).apply(LabelExtractor(testData))
    val testPredictions = predictionPipeline(testImagesAugmented)

    val testEvaluator = new AugmentedExamplesEvaluator(testImageIdsAugmented, numClasses)
    val testEval = testEvaluator.evaluate(testPredictions, testLabelsAugmented)
    logInfo(s"Test error is: ${testEval.totalError}")

    predictionPipeline
  }

  case class RandomPatchCifarAugmentedKernelConfig(
      trainLocation: String = "",
      testLocation: String = "",
      numFilters: Int = 100,
      whiteningEpsilon: Double = 0.1,
      patchSize: Int = 6,
      patchSteps: Int = 1,
      poolSize: Int = 10,
      poolStride: Int = 9,
      alpha: Double = 0.25,
      gamma: Double = 2e-4,
      cacheKernel: Boolean = true,
      blockSize: Int = 5000,
      numEpochs: Int = 1,
      seed: Option[Long] = None,
      lambda: Option[Double] = None,
      sampleFrac: Option[Double] = None,
      numRandomImagesAugment: Int = 10,
      checkpointDir: Option[String] = None)

  def parse(args: Array[String]): RandomPatchCifarAugmentedKernelConfig = {
    new OptionParser[RandomPatchCifarAugmentedKernelConfig](appName) {
      head(appName, "0.1")
      help("help") text("prints this usage text")
      opt[String]("trainLocation") required() action { (x,c) => c.copy(trainLocation=x) }
      opt[String]("testLocation") required() action { (x,c) => c.copy(testLocation=x) }
      opt[Int]("numFilters") action { (x,c) => c.copy(numFilters=x) }
      opt[Double]("whiteningEpsilon") required() action { (x,c) => c.copy(whiteningEpsilon=x) }
      opt[Int]("patchSize") action { (x,c) => c.copy(patchSize=x) }
      opt[Int]("patchSteps") action { (x,c) => c.copy(patchSteps=x) }
      opt[Int]("poolSize") action { (x,c) => c.copy(poolSize=x) }
      opt[Int]("numRandomImagesAugment") action { (x,c) => c.copy(numRandomImagesAugment=x) }
      opt[Double]("alpha") action { (x,c) => c.copy(alpha=x) }
      opt[Double]("gamma") action { (x,c) => c.copy(gamma=x) }
      opt[Boolean]("cacheKernel") action { (x,c) => c.copy(cacheKernel=x) }
      opt[Int]("blockSize") action { (x,c) => c.copy(blockSize=x) }
      opt[Int]("numEpochs") action { (x,c) => c.copy(numEpochs=x) }
      opt[Long]("seed") action { (x,c) => c.copy(seed=Some(x)) }
      opt[Double]("lambda") action { (x,c) => c.copy(lambda=Some(x)) }
      opt[Double]("sampleFrac") action { (x,c) => c.copy(sampleFrac=Some(x)) }
      opt[String]("checkpointDir") action { (x,c) => c.copy(checkpointDir=Some(x)) }
    }.parse(args, RandomPatchCifarAugmentedKernelConfig()).get
  }

  /**
   * The actual driver receives its configuration parameters from spark-submit usually.
   * @param args
   */
  def main(args: Array[String]) = {
    val appConfig = parse(args)

    val conf = new SparkConf().setAppName(appName)
    conf.setIfMissing("spark.master", "local[2]")
    // NOTE: ONLY APPLICABLE IF YOU CAN DONE COPY-DIR
    conf.remove("spark.jars")
    val sc = new SparkContext(conf)
    appConfig.checkpointDir.foreach(dir => sc.setCheckpointDir(dir))
    run(sc, appConfig)

    sc.stop()
  }
}
