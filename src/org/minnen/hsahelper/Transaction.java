package org.minnen.hsahelper;

import java.text.NumberFormat;
import java.text.ParseException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.jsoup.select.Elements;

public class Transaction
{
  public LocalDate date;
  public String    fund;
  public String    category;
  public double    price;
  public double    amount;
  public double    shares;
  public double    totalShares;
  public double    totalValue;

  public Transaction(Elements columns) throws ParseException
  {
    assert columns.size() == 9;
    String[] fields = new String[columns.size()];
    for (int i = 0; i < fields.length; ++i) {
      fields[i] = columns.get(i).html();
      if (fields[i].startsWith("$")) {
        fields[i] = fields[i].substring(1);
      } else if (fields[i].startsWith("($") && fields[i].endsWith(")")) {
        fields[i] = "-" + fields[i].substring(2, fields[i].length() - 1);
      }
    }

    NumberFormat nf = NumberFormat.getInstance();
    date = HSAReport.parseDate(fields[0]);
    fund = fields[1];
    category = fields[2];
    price = nf.parse(fields[4]).doubleValue();
    amount = nf.parse(fields[5]).doubleValue();
    shares = nf.parse(fields[6]).doubleValue();
    totalShares = nf.parse(fields[7]).doubleValue();
    totalValue = nf.parse(fields[8]).doubleValue();
  }

  public Transaction(Transaction transaction)
  {
    date = transaction.date;
    fund = transaction.fund;
    category = transaction.category;
    price = transaction.price;
    amount = transaction.amount;
    shares = transaction.shares;
    totalShares = transaction.totalShares;
    totalValue = transaction.totalValue;
  }

  @Override
  public String toString()
  {
    String fmt = (amount >= 0 ? "$%.2f" : "($%.2f)");
    String amountString = String.format(fmt, Math.abs(amount));
    return String.format("%s  %5s  %7.3f @ $%.2f = %s", date, fund, shares, price, amountString);
  }

  public static List<Transaction> deepCopy(List<Transaction> src)
  {
    List<Transaction> dst = new ArrayList<>();
    for (Transaction transaction : src) {
      dst.add(new Transaction(transaction));
    }
    return dst;
  }

  public static Comparator<Transaction> getPriceComparator(boolean ascending)
  {
    if (ascending) {
      return new Comparator<Transaction>()
      {
        @Override
        public int compare(Transaction a, Transaction b)
        {
          if (a.price < b.price) return -1;
          if (b.price < a.price) return 1;
          return 0;
        }
      };
    } else {
      return new Comparator<Transaction>()
      {
        @Override
        public int compare(Transaction a, Transaction b)
        {
          if (a.price < b.price) return 1;
          if (b.price < a.price) return -1;
          return 0;
        }
      };
    }
  }
}
