program TestForLoopWithBreakAndContinue;
var
	I: Integer;
begin
	for I := 1 to 10 do
  begin
    IF I = 3 THEN
    BEGIN
        continue;
    END;
    IF I = 8 THEN
    BEGIN
        break;
    END;
    WRITELN(I);
  end;
end.