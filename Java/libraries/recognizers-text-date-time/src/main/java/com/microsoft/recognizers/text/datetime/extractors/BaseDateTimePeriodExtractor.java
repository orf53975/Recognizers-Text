package com.microsoft.recognizers.text.datetime.extractors;

import com.microsoft.recognizers.text.ExtractResult;
import com.microsoft.recognizers.text.datetime.Constants;
import com.microsoft.recognizers.text.datetime.extractors.config.IDateTimePeriodExtractorConfiguration;
import com.microsoft.recognizers.text.datetime.extractors.config.ResultIndex;
import com.microsoft.recognizers.text.datetime.utilities.RegexExtension;
import com.microsoft.recognizers.text.datetime.utilities.Token;
import com.microsoft.recognizers.text.utilities.Match;
import com.microsoft.recognizers.text.utilities.RegExpUtility;
import com.microsoft.recognizers.text.utilities.StringUtility;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

public class BaseDateTimePeriodExtractor implements IDateTimeExtractor {

    private final IDateTimePeriodExtractorConfiguration config;

    @Override
    public String getExtractorName() {
        return Constants.SYS_DATETIME_DATETIMEPERIOD;
    }

    public BaseDateTimePeriodExtractor(IDateTimePeriodExtractorConfiguration config) {
        this.config = config;
    }

    @Override
    public List<ExtractResult> extract(String input) {
        return this.extract(input, LocalDateTime.now());
    }

    @Override
    public List<ExtractResult> extract(String input, LocalDateTime reference) {

        List<Token> tokens = new ArrayList<>();

        // Date and time Extractions should be extracted from the text only once, and shared in the methods below, passed by value
        List<ExtractResult> dateErs = config.getSingleDateExtractor().extract(input, reference);
        List<ExtractResult> timeErs = config.getSingleTimeExtractor().extract(input, reference);

        tokens.addAll(matchSimpleCases(input, reference));
        tokens.addAll(mergeTwoTimePoints(input, reference, new ArrayList<ExtractResult>(dateErs), new ArrayList<ExtractResult>(timeErs)));
        tokens.addAll(matchDuration(input, reference));
        tokens.addAll(matchTimeOfDay(input, reference, new ArrayList<ExtractResult>(dateErs)));
        tokens.addAll(matchRelativeUnit(input));
        tokens.addAll(matchDateWithPeriodPrefix(input, reference, new ArrayList<ExtractResult>(dateErs)));
        tokens.addAll(mergeDateWithTimePeriodSuffix(input, new ArrayList<ExtractResult>(dateErs), new ArrayList<ExtractResult>(timeErs)));

        return Token.mergeAllTokens(tokens, input, getExtractorName());
    }

    private List<Token> matchSimpleCases(String input, LocalDateTime reference) {
        List<Token> results = new ArrayList<>();

        for (Pattern regex : config.getSimpleCasesRegex()) {
            Match[] matches = RegExpUtility.getMatches(regex, input);
            for (Match match : matches) {
                // Is there a date before it?
                boolean hasBeforeDate = false;
                String beforeStr = input.substring(0, match.index);
                if (!StringUtility.isNullOrEmpty(beforeStr)) {
                    List<ExtractResult> ers = config.getSingleDateExtractor().extract(beforeStr, reference);
                    if (ers.size() > 0) {
                        ExtractResult er = ers.get(ers.size() - 1);
                        int begin = er.start;
                        String middleStr = beforeStr.substring(begin + er.length).trim().toLowerCase();
                        if (StringUtility.isNullOrEmpty(middleStr) || RegExpUtility.getMatches(config.getPrepositionRegex(), middleStr).length > 0) {
                            results.add(new Token(begin, match.index + match.length));
                            hasBeforeDate = true;
                        }
                    }
                }

                String followedStr = input.substring(match.index + match.length);
                if (!StringUtility.isNullOrEmpty(followedStr) && !hasBeforeDate) {
                    // Is it followed by a date?
                    List<ExtractResult> ers = config.getSingleDateExtractor().extract(followedStr, reference);
                    if (ers.size() > 0) {
                        ExtractResult er = ers.get(0);
                        int begin = er.start;
                        int end = er.start + er.length;
                        String middleStr = followedStr.substring(0, begin).trim().toLowerCase();
                        if (StringUtility.isNullOrEmpty(middleStr) || RegExpUtility.getMatches(config.getPrepositionRegex(), middleStr).length > 0) {
                            results.add(new Token(match.index, match.index + match.length + end));
                        }
                    }
                }
            }
        }

        return results;
    }

    private List<Token> mergeTwoTimePoints(String input, LocalDateTime reference, List<ExtractResult> dateErs, List<ExtractResult> timeErs) {

        List<Token> results = new ArrayList<>();
        List<ExtractResult> dateTimeErs = config.getSingleDateTimeExtractor().extract(input, reference);
        List<ExtractResult> timePoints = new ArrayList<>();

        // Handle the overlap problem
        int j = 0;
        for (int i = 0; i < dateTimeErs.size(); i++) {
            timePoints.add(dateTimeErs.get(i));

            while (j < timeErs.size() && timeErs.get(j).start + timeErs.get(j).length < timeErs.get(i).start) {
                timePoints.add(timeErs.get(j));
                j++;
            }
            
            while (j < timeErs.size() && timeErs.get(j).isOverlap(dateTimeErs.get(i))) {
                j++;
            }
        }

        for (; j < timeErs.size(); j++) {
            timePoints.add(timeErs.get(j));
        }

        timePoints.sort(Comparator.comparingInt(arg -> arg.start));

        // Merge "{TimePoint} to {TimePoint}", "between {TimePoint} and {TimePoint}"
        int idx = 0;
        while (idx < timePoints.size() - 1) {
            // If both ends are Time. then this is a TimePeriod, not a DateTimePeriod
            if (timePoints.get(idx).type.equals(Constants.SYS_DATETIME_TIME) && timePoints.get(idx + 1).type.equals(Constants.SYS_DATETIME_TIME)) {
                idx++;
                continue;
            }

            int middleBegin = timePoints.get(idx).start + timePoints.get(idx).length;
            int middleEnd = timePoints.get(idx + 1).start;

            String middleStr = input.substring(middleBegin, middleEnd).trim();

            // Handle "{TimePoint} to {TimePoint}"
            if (RegexExtension.isExactMatch(config.getTillRegex(), middleStr, true)) {
                int periodBegin = timePoints.get(idx).start;
                int periodEnd = timePoints.get(idx + 1).start + timePoints.get(idx + 1).length;

                // Handle "from"
                String beforeStr = input.substring(0, periodBegin).trim().toLowerCase();

                ResultIndex fromIndex = config.getFromTokenIndex(beforeStr);
                ResultIndex betweenIndex = config.getBetweenTokenIndex(beforeStr);
                if (fromIndex.result) {
                    periodBegin = fromIndex.index;
                } else if (betweenIndex.result) {
                    periodBegin = betweenIndex.index;
                }

                results.add(new Token(periodBegin, periodEnd));
                idx += 2;
                continue;
            }

            // Handle "between {TimePoint} and {TimePoint}"
            if (config.hasConnectorToken(middleStr)) {
                int periodBegin = timePoints.get(idx).start;
                int periodEnd = timePoints.get(idx + 1).start + timePoints.get(idx + 1).length;

                // Handle "between"
                String beforeStr = input.substring(0, periodBegin).trim().toLowerCase();

                ResultIndex betweenIndex = config.getBetweenTokenIndex(beforeStr);
                if (betweenIndex.result) {
                    periodBegin = betweenIndex.index;
                    results.add(new Token(periodBegin, periodEnd));
                    idx += 2;
                    continue;
                }
            }
            idx++;
        }

        // Regarding the pharse as-- {Date} {TimePeriod}, like "2015-9-23 1pm to 4"
        // Or {TimePeriod} on {Date}, like "1:30 to 4 on 2015-9-23"
        List<ExtractResult> timePeriodErs = config.getTimePeriodExtractor().extract(input, reference);
        dateErs.addAll(timePeriodErs);

        dateErs.sort(Comparator.comparingInt(arg -> arg.start));

        for (idx = 0; idx < dateErs.size() - 1; idx++) {
            if (dateErs.get(idx).type.equals(dateErs.get(idx + 1).type)) {
                continue;
            }

            int midBegin = dateErs.get(idx).start + dateErs.get(idx).length;
            int midEnd = dateErs.get(idx + 1).start;

            if (midEnd - midBegin > 0) {
                String midStr = input.substring(midBegin, midEnd);
                if (StringUtility.isNullOrWhiteSpace(midStr) || StringUtility.trimStart(midStr).startsWith(config.getTokenBeforeDate())) {
                    // Extend date extraction for cases like "Monday evening next week"
                    String extendedStr = dateErs.get(idx).text + input.substring(dateErs.get(idx + 1).start + dateErs.get(idx + 1).length);
                    Optional<ExtractResult> extendedDateEr = config.getSingleDateExtractor().extract(extendedStr).stream().findFirst();
                    int offset = 0;

                    if (extendedDateEr.isPresent() && extendedDateEr.get().start == 0) {
                        offset = extendedDateEr.get().length - dateErs.get(idx).length;
                    }

                    results.add(new Token(dateErs.get(idx).start, offset + dateErs.get(idx + 1).start + dateErs.get(idx + 1).length));
                    idx += 2;
                }
            }
        }

        return results;
    }

    //TODO: this can be abstracted with the similar method in BaseDatePeriodExtractor
    private List<Token> matchDuration(String input, LocalDateTime reference) {
        List<Token> results = new ArrayList<>();

        List<Token> durations = new ArrayList<>();
        List<ExtractResult> durationErs = config.getDurationExtractor().extract(input, reference);

        for (ExtractResult durationEr : durationErs) {
            Optional<Match> match = match(config.getTimeUnitRegex(), durationEr.text);
            if (match.isPresent()) {
                durations.add(new Token(durationEr.start, durationEr.start + durationEr.length));
            }
        }

        for (Token duration : durations) {
            String beforeStr = input.substring(0, duration.getStart()).toLowerCase();
            String afterStr = input.substring(duration.getStart() + duration.getLength());

            if (StringUtility.isNullOrWhiteSpace(beforeStr) && StringUtility.isNullOrWhiteSpace(afterStr)) {
                continue;
            }

            // within (the) (next) "Seconds/Minutes/Hours" should be handled as datetimeRange here
            // within (the) (next) XX days/months/years + "Seconds/Minutes/Hours" should also be handled as datetimeRange here
            Optional<Match> match = match(config.getWithinNextPrefixRegex(), beforeStr);
            if (matchPrefixRegexInSegment(beforeStr, match)) {
                int startToken = match.get().index;
                int durationLength = duration.getStart() + duration.getLength();
                match = match(config.getTimeUnitRegex(), input.substring(duration.getStart(), durationLength));
                if (match.isPresent()) {
                    results.add(new Token(startToken, duration.getEnd()));
                    continue;
                }
            }

            int index = -1;

            match = match(config.getPastPrefixRegex(), beforeStr);
            if (match.isPresent() && StringUtility.isNullOrWhiteSpace(beforeStr.substring(match.get().index + match.get().length))) {
                index = match.get().index;
            }

            if (index < 0) {
                match = match(config.getNextPrefixRegex(), beforeStr);
                if (match.isPresent() && StringUtility.isNullOrWhiteSpace(beforeStr.substring(match.get().index + match.get().length))) {
                    index = match.get().index;
                }
            }

            if (index >= 0) {

                String prefix = beforeStr.substring(0, index).trim();
                String durationText = input.substring(duration.getStart(), duration.getStart() + duration.getLength());
                List<ExtractResult> numbersInPrefix = config.getCardinalExtractor().extract(prefix);
                List<ExtractResult> numbersInDuration = config.getCardinalExtractor().extract(durationText);

                // Cases like "2 upcoming days", should be supported here
                // Cases like "2 upcoming 3 days" is invalid, only extract "upcoming 3 days" by default
                if (!numbersInPrefix.isEmpty() && numbersInDuration.isEmpty()) {
                    ExtractResult lastNumber = numbersInPrefix.stream()
                            .sorted(Comparator.comparingInt(x -> x.start + x.length))
                            .reduce((acc, item) -> item).orElse(null);

                    // Prefix should ends with the last number
                    if (lastNumber.start + lastNumber.length == prefix.length()) {
                        results.add(new Token(lastNumber.start, duration.getEnd()));
                    }

                } else {
                    results.add(new Token(index, duration.getEnd()));
                }

                continue;
            }

            Optional<Match> matchDateUnit = Arrays.stream(RegExpUtility.getMatches(config.getDateUnitRegex(), afterStr)).findFirst();
            if (!matchDateUnit.isPresent()) {
                match = Arrays.stream(RegExpUtility.getMatches(config.getPastPrefixRegex(), afterStr)).findFirst();
                if (match.isPresent() && StringUtility.isNullOrWhiteSpace(afterStr.substring(0, match.get().index))) {
                    results.add(new Token(duration.getStart(), duration.getStart() + duration.getLength() + match.get().index + match.get().length));
                    continue;
                }

                match = Arrays.stream(RegExpUtility.getMatches(config.getNextPrefixRegex(), afterStr)).findFirst();
                if (match.isPresent() && StringUtility.isNullOrWhiteSpace(afterStr.substring(0, match.get().index))) {
                    results.add(new Token(duration.getStart(), duration.getStart() + duration.getLength() + match.get().index + match.get().length));
                    continue;
                }

                match = Arrays.stream(RegExpUtility.getMatches(config.getFutureSuffixRegex(), afterStr)).findFirst();
                if (match.isPresent() && StringUtility.isNullOrWhiteSpace(afterStr.substring(0, match.get().index))) {
                    results.add(new Token(duration.getStart(), duration.getStart() + duration.getLength() + match.get().index + match.get().length));
                }
            }
        }

        return results;
    }

    private Optional<Match> match(Pattern regex, String input) {
        return Arrays.stream(RegExpUtility.getMatches(regex, input)).findFirst();
    }

    private boolean matchPrefixRegexInSegment(String beforeStr, Optional<Match> match) {
        return match.isPresent() && StringUtility.isNullOrWhiteSpace(beforeStr.substring(match.get().index + match.get().length));
    }

    private List<Token> matchTimeOfDay(String input, LocalDateTime reference, List<ExtractResult> dateErs) {

        List<Token> results = new ArrayList<>();
        Match[] matches = RegExpUtility.getMatches(config.getSpecificTimeOfDayRegex(), input);
        for (Match match : matches) {
            results.add(new Token(match.index, match.index + match.length));
        }

        // Date followed by morning, afternoon or morning, afternoon followed by Date
        if (dateErs.size() == 0) {
            return results;
        }

        for (ExtractResult er : dateErs) {
            String afterStr = input.substring(er.start + er.length);

            Optional<Match> match = Arrays.stream(RegExpUtility.getMatches(config.getPeriodTimeOfDayWithDateRegex(), afterStr)).findFirst();

            if (match.isPresent()) {
                // For cases like "Friday afternoon between 1PM and 4 PM" which "Friday afternoon" need to be extracted first
                if (StringUtility.isNullOrWhiteSpace(afterStr.substring(0, match.get().index))) {
                    int start = er.start;
                    int end = er.start + er.length
                            + match.get().getGroup(Constants.TimeOfDayGroupName).index
                            + match.get().getGroup(Constants.TimeOfDayGroupName).length;

                    results.add(new Token(start, end));
                    continue;
                }

                String connectorStr = afterStr.substring(0, match.get().index);

                // Trim here is set to false as the Regex might catch white spaces before or after the text
                boolean isMatch = RegexExtension.isExactMatch(config.getMiddlePauseRegex(), connectorStr, false);
                if (isMatch) {
                    String suffix = StringUtility.trimStart(afterStr.substring(match.get().index + match.get().length));

                    Optional<Match> endingMatch = Arrays.stream(RegExpUtility.getMatches(config.getGeneralEndingRegex(), suffix)).findFirst();

                    if (endingMatch.isPresent()) {
                        results.add(new Token(er.start, er.start + er.length + match.get().index + match.get().length));
                    }
                }
            }

            if (!match.isPresent()) {
                match = Arrays.stream(RegExpUtility.getMatches(config.getAmDescRegex(), afterStr)).findFirst();
            }

            if (!match.isPresent() || !StringUtility.isNullOrWhiteSpace(afterStr.substring(0, match.get().index))) {
                match = Arrays.stream(RegExpUtility.getMatches(config.getPmDescRegex(), afterStr)).findFirst();
            }

            if (match.isPresent()) {
                if (StringUtility.isNullOrWhiteSpace(afterStr.substring(0, match.get().index))) {
                    results.add(new Token(er.start, er.start + er.length + match.get().index + match.get().length));
                } else {
                    String connectorStr = afterStr.substring(0, match.get().index);
                    Optional<Match> pauseMatch = Arrays.stream(RegExpUtility.getMatches(config.getMiddlePauseRegex(), connectorStr)).findFirst();

                    if (pauseMatch.isPresent() && pauseMatch.get().length == connectorStr.length()) {
                        String suffix = afterStr.substring(match.get().index + match.get().length).replaceAll("^\\s+", "");

                        Optional<Match> endingMatch = Arrays.stream(RegExpUtility.getMatches(config.getGeneralEndingRegex(), suffix)).findFirst();
                        if (endingMatch.isPresent()) {
                            results.add(new Token(er.start, er.start + er.length + match.get().index + match.get().length));
                        }
                    }
                }
            }

            String prefixStr = input.substring(0, er.start);

            match = Arrays.stream(RegExpUtility.getMatches(config.getPeriodTimeOfDayWithDateRegex(), prefixStr)).findFirst();
            if (match.isPresent()) {
                if (StringUtility.isNullOrWhiteSpace(prefixStr.substring(match.get().index + match.get().length))) {
                    String midStr = input.substring(match.get().index + match.get().length, er.start);
                    if (!StringUtility.isNullOrEmpty(midStr) && StringUtility.isNullOrWhiteSpace(midStr)) {
                        results.add(new Token(match.get().index, er.start + er.length));
                    }
                } else {
                    String connectorStr = prefixStr.substring(match.get().index + match.get().length);

                    // Trim here is set to false as the Regex might catch white spaces before or after the text
                    Optional<Match> pauseMatch = Arrays.stream(RegExpUtility.getMatches(config.getMiddlePauseRegex(), connectorStr)).findFirst();

                    if (RegexExtension.isExactMatch(config.getMiddlePauseRegex(), connectorStr, false)) {
                        String suffix = StringUtility.trimStart(input.substring(er.start + er.length));

                        Optional<Match> endingMatch = Arrays.stream(RegExpUtility.getMatches(config.getGeneralEndingRegex(), suffix)).findFirst();
                        if (endingMatch.isPresent()) {
                            results.add(new Token(match.get().index, er.start + er.length));
                        }
                    }
                }
            }
        }

        // Check whether there are adjacent time period strings, before or after
        for (Token result : results.toArray(new Token[0])) {
            // Try to extract a time period in before-string 
            if (result.getStart() > 0) {
                String beforeStr = input.substring(0, result.getStart());
                if (!StringUtility.isNullOrEmpty(beforeStr)) {
                    List<ExtractResult> timeErs = config.getTimePeriodExtractor().extract(beforeStr);
                    if (timeErs.size() > 0) {
                        for (ExtractResult timeEr : timeErs) {
                            String midStr = beforeStr.substring(timeEr.start + timeEr.length);
                            if (StringUtility.isNullOrWhiteSpace(midStr)) {
                                results.add(new Token(timeEr.start, timeEr.start + timeEr.length + midStr.length() + result.getLength()));
                            }
                        }
                    }
                }
            }

            // Try to extract a time period in after-string
            if (result.getStart() + result.getLength() <= input.length()) {
                String afterStr = input.substring(result.getStart() + result.getLength());
                if (!StringUtility.isNullOrEmpty(afterStr)) {
                    List<ExtractResult> timeErs = config.getTimePeriodExtractor().extract(afterStr);
                    for (ExtractResult timeEr: timeErs) {
                        String midStr = afterStr.substring(0, timeEr.start);
                        if (StringUtility.isNullOrWhiteSpace(midStr)) {
                            results.add(new Token(result.getStart(), result.getStart() + result.getLength() + midStr.length() + timeEr.length));
                        }
                    }

                }
            }
        }

        return results;
    }

    private List<Token> matchRelativeUnit(String input) {
        List<Token> results = new ArrayList<Token>();

        Match[] matches = RegExpUtility.getMatches(config.getRelativeTimeUnitRegex(), input);
        if (matches.length == 0) {
            matches = RegExpUtility.getMatches(config.getRestOfDateTimeRegex(), input);
        }

        for (Match match : matches) {
            results.add(new Token(match.index, match.index + match.length));
        }

        return results;
    }

    private List<Token> matchDateWithPeriodPrefix(String input, LocalDateTime reference, List<ExtractResult> dateErs) {
        List<Token> results = new ArrayList<Token>();

        for (ExtractResult dateEr : dateErs) {
            int dateStrEnd = dateEr.start + dateEr.length;
            String beforeStr = input.substring(0, dateEr.start).trim();
            Optional<Match> match = Arrays.stream(RegExpUtility.getMatches(config.getPrefixDayRegex(), beforeStr)).findFirst();
            if (match.isPresent()) {
                results.add(new Token(match.get().index, dateStrEnd));
            }
        }

        return results;
    }

    // Cases like "today after 2:00pm", "1/1/2015 before 2:00 in the afternoon"
    private List<Token> mergeDateWithTimePeriodSuffix(String input, List<ExtractResult> dateErs, List<ExtractResult> timeErs) {
        List<Token> results = new ArrayList<Token>();

        if (dateErs.isEmpty()) {
            return results;
        }

        if (timeErs.isEmpty()) {
            return results;
        }

        List<ExtractResult> ers = new ArrayList<>();
        ers.addAll(dateErs);
        ers.addAll(timeErs);

        ers.sort(Comparator.comparingInt(arg -> arg.start));

        int i = 0;
        while (i < ers.size() - 1) {
            int j = i + 1;
            while (j < ers.size() && ers.get(i).isOverlap(ers.get(j))) {
                j++;
            }

            if (j >= ers.size()) {
                break;
            }

            if (ers.get(i).type.equals(Constants.SYS_DATETIME_DATE) && ers.get(j).type.equals(Constants.SYS_DATETIME_TIME)) {
                int middleBegin = ers.get(i).start + ers.get(i).length;
                int middleEnd = ers.get(j).start;
                if (middleBegin > middleEnd) {
                    i = j + 1;
                    continue;
                }

                String middleStr = input.substring(middleBegin, middleEnd).trim().toLowerCase();
                if (isValidConnectorForDateAndTimePeriod(middleStr)) {
                    int begin = ers.get(i).start;
                    int end = ers.get(j).start + ers.get(j).length;
                    results.add(new Token(begin, end));
                }

                i = j + 1;
                continue;
            }
            i = j;
        }

        // Handle "in the afternoon" at the end of entity
        for (int idx = 0; idx < results.size(); idx++) {
            String afterStr = input.substring(results.get(idx).getEnd());
            Optional<Match> match = Arrays.stream(RegExpUtility.getMatches(config.getSuffixRegex(), afterStr)).findFirst();
            if (match.isPresent()) {
                Token oldToken = results.get(idx);
                results.set(idx, new Token(oldToken.getStart(), oldToken.getEnd() + match.get().length));
            }
        }

        return results;
    }

    // Cases like "today after 2:00pm", "1/1/2015 before 2:00 in the afternoon"
    // Valid connector in English for Before include: "before", "no later than", "in advance of", "prior to", "earlier than", "sooner than", "by", "till", "until"...
    // Valid connector in English for After include: "after", "later than"
    private boolean isValidConnectorForDateAndTimePeriod(String text) {

        List<Pattern> beforeAfterRegexes = new ArrayList<>();
        beforeAfterRegexes.add(config.getBeforeRegex());
        beforeAfterRegexes.add(config.getAfterRegex());

        for (Pattern regex : beforeAfterRegexes) {
            Optional<Match> match = Arrays.stream(RegExpUtility.getMatches(regex, text)).findFirst();
            if (match.isPresent() && match.get().length == text.length()) {
                return true;
            }
        }

        return false;
    }
}
