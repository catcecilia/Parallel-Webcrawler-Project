package com.udacity.webcrawler;

import com.udacity.webcrawler.json.CrawlResult;
import com.udacity.webcrawler.parser.PageParser;
import com.udacity.webcrawler.parser.PageParserFactory;

import javax.inject.Inject;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
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

  private final int maxDepth;
  private final List<Pattern> ignoredUrls;



  @Inject
  ParallelWebCrawler(
          Clock clock,
          @Timeout Duration timeout,
          @PopularWordCount int popularWordCount,
          @TargetParallelism int threadCount,
          @MaxDepth int maxDepth,
          @IgnoredUrls List<Pattern> ignoredUrls)
  {
    this.clock = clock;
    this.timeout = timeout;
    this.popularWordCount = popularWordCount;
    this.pool = new ForkJoinPool(Math.min(threadCount, getMaxParallelism()));
    this.maxDepth = maxDepth;
    this.ignoredUrls = ignoredUrls;
  }

  @Inject
  PageParserFactory parserFactory;

  //To do: fill in crawl method
  //Method should download and parse HTML, reusing legacy code

  @Override
  public CrawlResult crawl(List<String> startingUrls) {
    Instant deadline = clock.instant().plus(timeout);
    Map<String, Integer> counter = Collections.synchronizedMap(new HashMap<>());
    Set<String> visitedUrls = new HashSet<>();

    for (String url : startingUrls) {
      pool.invoke(new InternalCrawler(url, deadline, maxDepth, counter, visitedUrls));
    }

    //if counter is empty, call build function to build the stats of the computed word count and visitedURL size
    if (counter.isEmpty()) {
      return new CrawlResult.Builder()
              .setWordCounts(counter)
              .setUrlsVisited(visitedUrls.size())
              .build();
    }
    //if counter is NOT empty, show popular words by highest to lowest as well as visited  size
    return new CrawlResult.Builder()
            .setWordCounts(WordCounts.sort(counter,popularWordCount))
            .setUrlsVisited(visitedUrls.size())
            .build();

  }

  //extension of recursiveaction allows for subtasks to be done in a manner that it's recursive

  private class InternalCrawler extends RecursiveAction {
    String url;
    Instant deadline;
    int maxDepth;
    Map<String, Integer> counter;
    Set<String> visitedUrls;
    List<InternalCrawler> subTasks;

    private InternalCrawler(String url, Instant deadline, int maxDepth, Map<String, Integer> counter, Set<String> visitedUrls) {
      this.url = url;
      this.deadline = deadline;
      this.maxDepth  = maxDepth;
      this.counter = counter;
      this.visitedUrls = visitedUrls;

    }

    @Override
    protected void compute() {
      //not counted if it doesn't exist or is after the deadline
      if (maxDepth == 0 || clock.instant().isAfter(deadline)) {
        return;
      }

      //the same URL will not be counted twice
      for (Pattern pattern : ignoredUrls) {
        if(pattern.matcher(url).matches()){
          return;
        }
      }

      if(!visitedUrls.add(url)){
        return;
      }

      PageParser.Result result = parserFactory.get(url).parse();

      //if the word is found, update the value so that it increases.
      for (Map.Entry<String, Integer> e : result.getWordCounts().entrySet()) {
        if (counter.containsKey(e.getKey())) {
          counter.put(e.getKey(), e.getValue() + counter.get(e.getKey()));
        } else {
          counter.put(e.getKey(), e.getValue());
        }
      }

      subTasks = new ArrayList();
      //recursive action
      for (String link : result.getLinks()) {
        subTasks.add(new InternalCrawler(link, deadline, (maxDepth - 1), counter, visitedUrls));
      }

      //wait for the completion of all subtasks to return result
      invokeAll(subTasks);
    }
  }

  @Override
  public int getMaxParallelism() {
    return Runtime.getRuntime().availableProcessors();
  }
}
