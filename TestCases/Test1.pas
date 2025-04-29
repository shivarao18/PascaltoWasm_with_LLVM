program ScopeTest;
var
  c, d, e: Integer;
begin
  c := 15;
  d := 10;
  e := c + d;
  writeln('Global c: ', c);
  writeln('Global d: ', d);
  writeln('Global e: ', e);
end.