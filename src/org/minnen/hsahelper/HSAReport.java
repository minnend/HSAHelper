package org.minnen.hsahelper;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.time.LocalDate;
import java.time.Month;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

public class HSAReport
{
  private final static Pattern           dateRangePattern;
  private final static DateTimeFormatter dtfYear2, dtfYear4;

  static {
    dateRangePattern = Pattern.compile("Date Range:\\s*(\\d+/\\d+/\\d+)\\s*to\\s*(\\d+/\\d+/\\d+)");
    dtfYear2 = DateTimeFormatter.ofPattern("M/d/yy");
    dtfYear4 = DateTimeFormatter.ofPattern("M/d/yyyy");
  }

  public static boolean eq(double a, double b)
  {
    return Math.abs(a - b) < 1e-4;
  }

  public static LocalDate parseDate(String date)
  {
    try {
      return LocalDate.parse(date, dtfYear4);
    } catch (DateTimeParseException e) {
      return LocalDate.parse(date, dtfYear2);
    }
  }

  public static boolean isLTG(LocalDate buyDate, LocalDate sellDate)
  {
    if (sellDate.isBefore(buyDate)) { // handle nonsense case of sell before buy
      return false;
    }

    Period period = Period.between(buyDate, sellDate);
    assert period.getMonths() < 12;
    if (period.getYears() < 1) return false;
    if (period.getYears() > 1) return true;
    assert period.getYears() == 1;

    // If sell date is Feb 29, must hold one extra day.
    int nMinDays = (sellDate.getMonth() == Month.FEBRUARY && sellDate.getDayOfMonth() == 29 ? 2 : 1);
    return period.getMonths() > 0 || period.getDays() >= nMinDays;
  }

  public static double reduceShares(double total, double subtract)
  {
    total -= subtract;
    if (Math.abs(total) < 1e-4) total = 0.0;
    return total;
  }

  public static void printDividendsByYear(List<Transaction> transactions)
  {
    LocalDate prevDate = null;
    double dividends = 0.0;
    int count = 0;
    System.out.println("Total Dividends Per Year");
    for (Transaction transaction : transactions) {
      if (!transaction.category.equals("Dividend")) continue;
      if (prevDate == null || prevDate.getYear() == transaction.date.getYear()) {
        dividends += transaction.amount;
        ++count;
      } else if (count > 0) {
        System.out.printf(" %d  %7.2f  %3d\n", prevDate.getYear(), dividends, count);
        dividends = transaction.amount;
        count = 1;
      }
      prevDate = transaction.date;
    }
    if (count > 0) {
      System.out.printf(" %d  %7.2f  %3d\n", prevDate.getYear(), dividends, count);
    }
  }

  /** @return map of fund names to transaction for that fund. */
  public static Map<String, List<Transaction>> splitByFund(List<Transaction> transactions)
  {
    Map<String, List<Transaction>> byFund = new TreeMap<>();
    for (Transaction transaction : transactions) {
      List<Transaction> list = byFund.getOrDefault(transaction.fund, null);
      if (list == null) {
        list = new ArrayList<>();
        byFund.put(transaction.fund, list);
      }
      list.add(transaction);
    }
    return byFund;
  }

  /** @return list of buy orders that lost money (price is higher than sell order). */
  public static List<Transaction> getLosers(int sellIndex, List<Transaction> transactions)
  {
    Transaction sell = transactions.get(sellIndex);
    assert sell.category.equals("Sell") : sell;

    List<Transaction> losses = new ArrayList<>();
    for (Transaction buy : transactions) {
      if (buy.category.equals("Sell")) continue;
      assert buy.category.equals("Buy") || buy.category.equals("Dividend") : buy;
      if (!buy.date.isBefore(sell.date)) continue;
      if (buy.shares <= 0) continue;
      if (buy.price > sell.price) {
        losses.add(buy);
      }
    }
    losses.sort(Transaction.getPriceComparator(false));
    return losses;
  }

  /** @return list of buy orders that gained money (price is lower than sell order). */
  public static List<Transaction> getWinners(int sellIndex, List<Transaction> transactions)
  {
    Transaction sell = transactions.get(sellIndex);
    assert sell.category.equals("Sell") : sell;

    List<Transaction> shortTerm = new ArrayList<>();
    List<Transaction> longTerm = new ArrayList<>();
    for (Transaction buy : transactions) {
      if (buy.category.equals("Sell")) continue;
      assert buy.category.equals("Buy") || buy.category.equals("Dividend") : buy.category;
      if (!buy.date.isBefore(sell.date)) continue;
      if (buy.shares <= 0) continue;
      if (buy.price > sell.price) continue;

      if (isLTG(buy.date, sell.date)) {
        longTerm.add(buy);
      } else {
        shortTerm.add(buy);
      }
    }

    longTerm.sort(Transaction.getPriceComparator(false));
    shortTerm.sort(Transaction.getPriceComparator(false));

    longTerm.addAll(shortTerm); // long-term gains come first
    return longTerm;
  }

  public static void printCapGains(List<Transaction> transactionsForAllFunds)
  {
    Map<String, List<Transaction>> byFund = splitByFund(transactionsForAllFunds);

    for (Map.Entry<String, List<Transaction>> entry : byFund.entrySet()) {
      List<Transaction> transactions = Transaction.deepCopy(entry.getValue());

      double ltg = 0.0;
      double stg = 0.0;
      double costBasis = 0.0;
      for (int sellIndex = 0; sellIndex < transactions.size(); ++sellIndex) {
        Transaction sell = transactions.get(sellIndex);
        if (!sell.category.equals("Sell")) continue;

        System.out.println(sell);
        assert sell.shares < 0;

        List<Transaction> buys = new ArrayList<>();
        buys.addAll(getLosers(sellIndex, transactions));
        buys.addAll(getWinners(sellIndex, transactions));

        double sharesToMatch = -sell.shares;
        double sharesFound = 0;
        for (Transaction buy : buys) {
          assert buy.category.equals("Buy") || buy.category.equals("Dividend") : buy;
          assert buy.shares > 0 : buy;
          // System.out.printf(" %s\n", buy);

          boolean isLongTerm = isLTG(buy.date, sell.date);

          double shares = Math.min(buy.shares, sharesToMatch);
          sharesFound += shares;
          double buyValue = shares / buy.shares * buy.amount;
          costBasis += buyValue;
          buy.shares = reduceShares(buy.shares, shares);
          sharesToMatch = reduceShares(sharesToMatch, shares);

          double sellValue = shares / sell.shares * sell.amount;
          double gain = sellValue - buyValue;
          if (isLongTerm) ltg += gain;
          else stg += gain;
        }

        System.out.printf("   LTG: %8.2f\n", ltg);
        System.out.printf("   STG: %8.2f\n", stg);
        System.out.printf(" Total: %8.2f\n", ltg + stg);
        System.out.printf(" Basis: %8.2f\n", costBasis);
        assert eq(-sell.shares, sharesFound);
        assert eq(-sell.amount, costBasis + ltg + stg);
      }
    }
  }

  public static void main(String[] args) throws IOException, ParseException
  {
    if (args.length != 1) {
      System.err.println("Missing HSA report glob.");
    }
    File globFile = new File(args[0]);
    String dir = globFile.getParent();
    if (dir == null) dir = ".";
    String glob = globFile.getName();

    System.out.printf("glob: [%s] + [%s]\n", dir, glob);
    List<File> files = new ArrayList<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get(dir), glob)) {
      for (Path path : stream) {
        files.add(path.toFile());
      }
    }
    System.out.printf("files: %d\n", files.size());
    List<Transaction> transactions = new ArrayList<>();
    for (File file : files) {
      System.out.printf("%s\n", file.getName());
      Document doc = Jsoup.parse(file, "UTF-8");
      Elements rows = doc.getElementsByTag("tr");
      int row_index = 0;
      for (Element row : rows) {
        Elements columns = row.getElementsByTag("td");
        if (row_index == 0) {
          assert columns.size() == 1;
          assert columns.get(0).html().contains("All Investment Transactions");
        } else if (row_index == 1) {
          assert columns.size() == 1;
          String html = columns.get(0).html();
          Matcher m = dateRangePattern.matcher(html);
          if (!m.find()) {
            System.err.printf("Invalid date range: %s\n", html);
            System.exit(1);
          }
          LocalDate dateFrom = parseDate(m.group(1));
          LocalDate dateTo = parseDate(m.group(2));
          System.out.printf(" Dates: %s -> %s\n", dateFrom, dateTo);
          // TODO parse dates and check for gaps
        } else {
          if (row_index == 2) {
            assert columns.size() == 9;
            String dateString = columns.get(0).html();
            String fund = columns.get(1).html();
            assert dateString.contains("Date") : dateString;
            assert fund.contains("Fund") : fund;
            // TODO check other headers; better = read headers and map column index
          } else {
            transactions.add(new Transaction(columns));
          }
        }
        ++row_index;
      }
      System.out.printf(" Transactions: %d\n", transactions.size());
    }
    if (files.size() > 1) {
      System.out.printf("Total Transactions: %d\n", transactions.size());
    }

    printDividendsByYear(transactions);
    System.out.println();
    printCapGains(transactions);
  }
}
