package com.udacity.webcrawler;

import com.udacity.webcrawler.json.CrawlResult;
import com.udacity.webcrawler.parser.PageParser;
import com.udacity.webcrawler.parser.PageParserFactory;

import javax.inject.Inject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.RecursiveTask;
import java.util.regex.Pattern;

/**
 * A concrete implementation of {@link WebCrawler} that runs multiple threads on a
 * {@link ForkJoinPool} to fetch and process multiple web pages in parallel.
 */
final class ParallelWebCrawler implements WebCrawler {
  private final Clock clock;
  private final Duration timeout;
  private final int popularWordCount;
  private final ForkJoinPool pool;

  private int maxDepth;
  private final List<Pattern> ignoredUrls;

  @Inject
  ParallelWebCrawler(
          Clock clock,
          @Timeout Duration timeout,
          @PopularWordCount int popularWordCount,
          @TargetParallelism int threadCount, List<Pattern> ignoredUrls) {
    this.clock = clock;
    this.timeout = timeout;
    this.popularWordCount = popularWordCount;
    this.pool = new ForkJoinPool(Math.min(threadCount, getMaxParallelism()));
    this.maxDepth = maxDepth; //ok to set to 0?
    this.ignoredUrls = ignoredUrls;
  }

  @Inject
  PageParserFactory parserFactory;
  @Override
  public CrawlResult crawl(List<String> startingUrls) {
    Instant deadline = clock.instant().plus(timeout);
    Map<String, Integer> counter = Collections.synchronizedMap(new HashMap<>());
    Set<String> visitedUrlSet = new HashSet<>();

    for (String url: startingUrls) {
      pool.invoke(new InternalCrawler(url, deadline, maxDepth, counter, visitedUrlSet));
    }
    if (counter.isEmpty()) {
      return new CrawlResult.Builder()
              .setWordCounts(counter)
              .setUrlsVisited(visitedUrlSet.size())
              .build();
    }
    return new CrawlResult.Builder()
            .setWordCounts(WordCounts.sort(counter,popularWordCount))
            .setUrlsVisited(visitedUrlSet.size())
            .build();
  }

  public class InternalCrawler extends RecursiveAction{
    private String url;
    private Instant deadline;
    private int maxDepth;
    Map<String, Integer> counter;
    Set<String> visitedUrlSet;
    List<InternalCrawler> subtasks;

    private InternalCrawler(String url, Instant deadline, Integer maxDepth, Map<String, Integer> counter, Set<String> VisitedUrlSet) {
      this.url = url;
      this.deadline = deadline;
      this.maxDepth = maxDepth;
      this.counter = counter;
      this.visitedUrlSet = visitedUrlSet;
      this.subtasks = subtasks;
    }

    @Override
    protected void compute() {
      for (Pattern pattern : ignoredUrls) {
        if(pattern.matcher(url).matches()){
          return;
        }
      }
      if (visitedUrlSet.contains(url)){
        return;
      }
      if (clock.instant().isAfter(deadline) || maxDepth == 0) {
        return;
      }
      visitedUrlSet.add(url);
      PageParser.Result results = parserFactory.get(url).parse();

      for (Map.Entry<String, Integer> any : results.getWordCounts().entrySet()){
          if (counter.containsKey(any.getKey())){
            counter.put(any.getKey(),any.getValue() + counter.get(any.getKey()));
          }
          else{
            counter.put(any.getKey(), any.getValue());
          }
      }
      subtasks = new ArrayList<>();
      for (String links : results.getLinks()) {
        subtasks.add(new InternalCrawler(links, deadline, (maxDepth - 1), counter, visitedUrlSet));
      }
      invokeAll(subtasks);
    }
  }

  @Override
  public int getMaxParallelism() {
    return Runtime.getRuntime().availableProcessors();
  }
}
